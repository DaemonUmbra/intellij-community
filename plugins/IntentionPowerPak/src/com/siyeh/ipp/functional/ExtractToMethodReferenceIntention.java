/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package com.siyeh.ipp.functional;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.CodeInsightUtilCore;
import com.intellij.codeInsight.intention.BaseElementAtCaretIntentionAction;
import com.intellij.codeInspection.LambdaCanBeMethodReferenceInspection;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pass;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.extractMethod.ControlFlowWrapper;
import com.intellij.refactoring.extractMethod.ExtractMethodProcessor;
import com.intellij.refactoring.extractMethod.PrepareFailedException;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.refactoring.rename.inplace.MemberInplaceRenamer;
import com.intellij.refactoring.util.LambdaRefactoringUtil;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.refactoring.util.duplicates.MethodDuplicatesHandler;
import com.intellij.util.ObjectUtils;
import com.intellij.util.text.UniqueNameGenerator;
import com.siyeh.IntentionPowerPackBundle;
import com.siyeh.ig.psiutils.CommentTracker;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ExtractToMethodReferenceIntention extends BaseElementAtCaretIntentionAction {
  private static final Logger LOG = Logger.getInstance(ExtractToMethodReferenceIntention.class);

  @NotNull
  @Override
  public String getText() {
    return getFamilyName();
  }

  @NotNull
  @Override
  public String getFamilyName() {
    return IntentionPowerPackBundle.message("extract.to.method.reference.intention.name");
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
    final PsiLambdaExpression lambdaExpression = PsiTreeUtil.getParentOfType(element, PsiLambdaExpression.class, false);
    if (lambdaExpression != null) {
      PsiElement body = lambdaExpression.getBody();
      if (body == null) return false;

      //is a valid lambda
      PsiType functionalInterfaceType = lambdaExpression.getFunctionalInterfaceType();
      if (functionalInterfaceType == null ||
          LambdaUtil.getFunctionalInterfaceReturnType(functionalInterfaceType) == null) {
        return false;
      }

      //can types be specified
      if (LambdaRefactoringUtil.createLambdaParameterListWithFormalTypes(functionalInterfaceType, lambdaExpression, false) == null) {
        return false;
      }

      PsiExpression asMethodReference = LambdaCanBeMethodReferenceInspection
        .canBeMethodReferenceProblem(body, lambdaExpression.getParameterList().getParameters(), functionalInterfaceType, null);
      if (asMethodReference != null) return false;
      try {
        PsiElement[] toExtract = body instanceof PsiCodeBlock ? ((PsiCodeBlock)body).getStatements() : new PsiElement[]{body};
        ControlFlowWrapper wrapper = new ControlFlowWrapper(project, body, toExtract);
        wrapper.prepareExitStatements(toExtract, body);
        PsiVariable[] outputVariables = wrapper.getOutputVariables();
        List<PsiVariable> inputVariables = wrapper.getInputVariables(body, toExtract, outputVariables);
        return inputVariables.stream()
          .allMatch(variable -> variable instanceof PsiParameter && ((PsiParameter)variable).getDeclarationScope() == lambdaExpression);
      }
      catch (PrepareFailedException ignored) {
      }
      catch (ControlFlowWrapper.ExitStatementsNotSameException ignored) {
      }
    }
    return false;
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
    PsiLambdaExpression lambdaExpression = PsiTreeUtil.getParentOfType(element, PsiLambdaExpression.class, false);
    if (lambdaExpression != null) {
      PsiCodeBlock body = RefactoringUtil.expandExpressionLambdaToCodeBlock(lambdaExpression);

      PsiClass targetClass = PsiTreeUtil.getParentOfType(lambdaExpression, PsiClass.class);
      if (targetClass == null) return;
      PsiElement[] elements = body.getStatements();

      HashSet<PsiField> usedFields = new HashSet<>();
      boolean canBeStatic = ExtractMethodProcessor.canBeStatic(targetClass, lambdaExpression, elements, usedFields) && usedFields.isEmpty();
      PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(targetClass.getProject());
      PsiType functionalInterfaceType = lambdaExpression.getFunctionalInterfaceType();

      String parameters =
        LambdaRefactoringUtil.createLambdaParameterListWithFormalTypes(functionalInterfaceType, lambdaExpression, false) + "{}";
      String targetMethodName = getUniqueMethodName(targetClass, elementFactory, functionalInterfaceType, parameters);

      PsiType returnType = LambdaUtil.getFunctionalInterfaceReturnType(lambdaExpression);
      LOG.assertTrue(returnType != null);
      PsiMethod container = PsiTreeUtil.getParentOfType(lambdaExpression, PsiMethod.class);
      PsiTypeParameterList typeParamsList =
        container != null
        ? RefactoringUtil.createTypeParameterListWithUsedTypeParameters(container.getTypeParameterList(), elements)
        : null;
      PsiMethod emptyMethod = elementFactory.createMethodFromText("private " + (canBeStatic ? "static " : "") +
                                                                  (typeParamsList != null ? typeParamsList.getText() + " " : "") +
                                                                  returnType.getCanonicalText() + " " +
                                                                  targetMethodName + parameters, targetClass);
      PsiCodeBlock targetMethodBody = emptyMethod.getBody();
      LOG.assertTrue(targetMethodBody != null);
      if (elements.length > 0) targetMethodBody.addRange(elements[0], elements[elements.length - 1]);

      PsiMethod method = (PsiMethod)CodeStyleManager.getInstance(project)
        .reformat(JavaCodeStyleManager.getInstance(project).shortenClassReferences(targetClass.add(emptyMethod)));
      PsiMethodReferenceExpression methodReference =
        (PsiMethodReferenceExpression)elementFactory
          .createExpressionFromText((canBeStatic ? targetClass.getName() : "this") + "::" + targetMethodName, lambdaExpression);
      CommentTracker tracker = new CommentTracker();
      methodReference = (PsiMethodReferenceExpression)tracker.replace(lambdaExpression, methodReference);
      tracker.insertCommentsBefore(methodReference);

      startInplaceRename(editor, method, methodReference);
    }
  }

  private static void startInplaceRename(Editor editor, PsiMethod method, PsiMethodReferenceExpression methodReference) {
    PsiIdentifier nameIdentifier = method.getNameIdentifier();
    if (nameIdentifier == null) return;
    nameIdentifier = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(nameIdentifier);

    //try to navigate to reference name
    editor.getCaretModel().moveToOffset(ObjectUtils.notNull(methodReference.getReferenceNameElement(), nameIdentifier).getTextOffset());

    final RenamePsiElementProcessor processor = RenamePsiElementProcessor.forElement(method);
    if (!processor.isInplaceRenameSupported()) {
      return;
    }
    List<String> suggestedNames = new ArrayList<>();
    suggestedNames.add(method.getName());
    processor.substituteElementToRename(method, editor, new Pass<PsiElement>() {
      @Override
      public void pass(PsiElement substitutedElement) {
        final MemberInplaceRenamer renamer = new MemberInplaceRenamer(method, substitutedElement, editor) {
          @Override
          protected boolean performRefactoring() {
            if (super.performRefactoring()) {
              ApplicationManager.getApplication().invokeLater(() -> processMethodsDuplicates(method));
              return true;
            }
            return false;
          }
        };
        final LinkedHashSet<String> nameSuggestions = new LinkedHashSet<>(suggestedNames);
        renamer.performInplaceRefactoring(nameSuggestions);
      }
    });
  }

  private static void processMethodsDuplicates(PsiMethod method) {
    final Runnable runnable = () -> {
      if (!method.isValid()) return;
      MethodDuplicatesHandler
        .invokeOnScope(method.getProject(), Collections.singleton(method), new AnalysisScope(method.getContainingFile()), true);
    };
    ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> ApplicationManager.getApplication().runReadAction(runnable),
                                                                      MethodDuplicatesHandler.REFACTORING_NAME, true, method.getProject());
  }

  private static String getUniqueMethodName(PsiClass targetClass,
                                            PsiElementFactory elementFactory,
                                            PsiType functionalInterfaceType,
                                            String parameters) {
    PsiMethod interfaceMethod = LambdaUtil.getFunctionalInterfaceMethod(functionalInterfaceType);
    String initialMethodName = interfaceMethod != null ? interfaceMethod.getName() : "name";
    return UniqueNameGenerator.generateUniqueName(initialMethodName,
                                                  methodName -> {
                                                    String methodText = "private void " + methodName + parameters;
                                                    PsiMethod patternMethod = elementFactory.createMethodFromText(methodText, targetClass);
                                                    return targetClass.findMethodBySignature(patternMethod, true) == null;
                                                  });
  }
}

