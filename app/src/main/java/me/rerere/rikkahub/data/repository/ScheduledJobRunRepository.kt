package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.ScheduledJobRunDao
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity

class ScheduledJobRunRepository(private val dao: ScheduledJobRunDao) {
    suspend fun getById(id: String) = dao.getById(id)
    suspend fun getRecent(jobId: String, limit: Int) = dao.getRecent(jobId, limit)
    fun observeRecent(jobId: String, limit: Int): Flow<List<ScheduledJobRunEntity>> =
        dao.observeRecent(jobId, limit)
    suspend fun getStranded(stalenessMs: Long) = dao.getStranded(stalenessMs)
    suspend fun insert(row: ScheduledJobRunEntity) = dao.insert(row)
    suspend fun update(row: ScheduledJobRunEntity): Int = dao.update(row)
    suspend fun trim(jobId: String, keep: Int) = dao.trim(jobId, keep)
    suspend fun deleteAllForJob(jobId: String) = dao.deleteAllForJob(jobId)
    suspend fun getMostRecent(jobId: String) = dao.getMostRecent(jobId)
    suspend fun getMostRecentNonSkipForSlot(jobId: String, slotMs: Long) =
        dao.getMostRecentNonSkipForSlot(jobId, slotMs)
    suspend fun countSuccessful(jobId: String) = dao.countSuccessful(jobId)
}
