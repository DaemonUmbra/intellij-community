// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.workspaceModel.storage.impl.indices

import com.intellij.util.containers.BidirectionalMap
import com.intellij.workspaceModel.storage.impl.EntityId
import com.intellij.workspaceModel.storage.impl.containers.copy

open class EntityStorageInternalIndex<T> private constructor(
  internal open val index: BidirectionalMap<EntityId, T>
) {
  constructor() : this(BidirectionalMap<EntityId, T>())

  internal fun getIdsByEntry(entitySource: T): List<EntityId>? =
    index.getKeysByValue(entitySource)

  internal fun getEntryById(id: EntityId): T? = index[id]

  internal fun entries(): Collection<T> {
    return index.values
  }

  class MutableEntityStorageInternalIndex<T> private constructor(
    override var index: BidirectionalMap<EntityId, T>
  ) : EntityStorageInternalIndex<T>(index) {

    private var freezed = true

    internal fun index(id: EntityId, entitySource: T? = null) {
      startWrite()
      index.remove(id)
      if (entitySource == null) return
      index[id] = entitySource
    }

    private fun startWrite() {
      if (!freezed) return
      freezed = false
      index = copyIndex()
    }

    private fun copyIndex(): BidirectionalMap<EntityId, T> = index.copy()

    fun toImmutable(): EntityStorageInternalIndex<T> {
      freezed = true
      return EntityStorageInternalIndex(index)
    }

    companion object {
      fun <T> from(other: EntityStorageInternalIndex<T>): MutableEntityStorageInternalIndex<T> {
        return MutableEntityStorageInternalIndex(other.index)
      }
    }
  }
}

