package com.posepocket.pose

import java.io.File

/** Durable unsaved result. Commit the result before clearing the remote-processing checkpoint. */
class CompletedResultStore(root:File) {
    private val repository=ProjectRepository(root)
    @Synchronized fun save(clip:PoseClip):PoseClip {
        val saved=repository.save(clip)
        val result=repository.open(saved.id)
        repository.list().filter { it.id!=saved.id }.forEach { repository.delete(it.id) }
        return result
    }
    @Synchronized fun load():PoseClip?=repository.list().firstOrNull()?.let { repository.open(it.id) }
    @Synchronized fun clear()=repository.deleteAll()
}
