/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.jps.incremental

import com.intellij.util.containers.MultiMap
import org.jetbrains.annotations.TestOnly
import org.jetbrains.jps.builders.storage.StorageProvider
import org.jetbrains.kotlin.incremental.components.LocationInfo
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.jps.incremental.storage.*
import org.jetbrains.kotlin.utils.Printer
import java.io.File
import java.util.*

object LOOKUP_TRACKER_STORAGE_PROVIDER : StorageProvider<LookupStorage>() {
    override fun createStorage(targetDataDir: File): LookupStorage = LookupStorage(targetDataDir)
}

class LookupStorage(private val targetDataDir: File) : BasicMapsOwner() {
    companion object {
        private val DELETED_TO_SIZE_TRESHOLD = 0.5
        private val MINIMUM_GARBAGE_COLLECTIBLE_SIZE = 10000
    }

    private val String.storageFile: File
        get() = File(targetDataDir, this + "." + CACHE_EXTENSION)

    private val countersFile = "counters".storageFile
    private val idToFile = registerMap(IdToFileMap("id-to-file".storageFile))
    private val fileToId = registerMap(FileToIdMap("file-to-id".storageFile))
    private val lookupMap = registerMap(LookupMap("lookups".storageFile))
    private var size: Int = 0
    private var deletedCount: Int = 0

    init {
        if (countersFile.exists()) {
            val lines = countersFile.readLines()
            size = lines[0].toInt()
            deletedCount = lines[1].toInt()
        }
    }

    public fun add(lookupSymbol: LookupSymbol, containingFiles: Collection<File>) {
        val key = lookupSymbol.toHashPair()
        val fileIds = containingFiles.map { addFileIfNeeded(it) }.toHashSet()
        fileIds.addAll(lookupMap[key] ?: emptySet())
        lookupMap[key] = fileIds
    }

    public fun removeLookupsFrom(file: File) {
        val id = fileToId[file] ?: return
        idToFile.remove(id)
        fileToId.remove(file)
        deletedCount++
    }

    override fun clean() {
        if (countersFile.exists()) {
            countersFile.delete()
        }

        size = 0
        deletedCount = 0

        super.clean()
    }

    override fun flush(memoryCachesOnly: Boolean) {
        try {
            removeGarbageIfNeeded()
            countersFile.writeText("$size\n$deletedCount")
        }
        finally {
            super.flush(memoryCachesOnly)
        }
    }

    private fun addFileIfNeeded(file: File): Int {
        val existing = fileToId[file]
        if (existing != null) return existing

        val id = size++
        fileToId[file] = id
        idToFile[id] = file
        return id
    }

    private fun removeGarbageIfNeeded() {
        if (size <= MINIMUM_GARBAGE_COLLECTIBLE_SIZE && deletedCount.toDouble() / size <= DELETED_TO_SIZE_TRESHOLD) return

        for (hash in lookupMap.keys) {
            lookupMap[hash] = lookupMap[hash]!!.filter { it in idToFile }.toSet()
        }

        size = 0
        deletedCount = 0
        idToFile.clean()

        fileToId.files.forEach { addFile(it) }
}

class LookupTrackerImpl(private val delegate: LookupTracker) : LookupTracker {
    val lookups = MultiMap<LookupSymbol, File>()

    override fun record(locationInfo: LocationInfo, scopeFqName: String, scopeKind: ScopeKind, name: String) {
        lookups.putValue(LookupSymbol(name, scopeFqName), File(locationInfo.filePath))
        delegate.record(locationInfo, scopeFqName, scopeKind, name)
    }
}

data class LookupSymbol(val name: String, val scope: String)

fun LookupSymbol.toHashPair() = LookupHashPair(name, scope)
