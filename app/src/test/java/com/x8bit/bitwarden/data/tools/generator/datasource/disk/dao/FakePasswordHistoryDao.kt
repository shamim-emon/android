package com.x8bit.bitwarden.data.tools.generator.datasource.disk.dao

import com.x8bit.bitwarden.data.tools.generator.datasource.disk.entity.PasswordHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

class FakePasswordHistoryDao : PasswordHistoryDao {
    val storedPasswordHistories = mutableListOf<PasswordHistoryEntity>()

    private val passwordHistoriesFlow = MutableSharedFlow<List<PasswordHistoryEntity>>(
        replay = 1,
        extraBufferCapacity = Int.MAX_VALUE,
    )

    init {
        passwordHistoriesFlow.tryEmit(emptyList())
    }

    override suspend fun insertPasswordHistory(passwordHistory: PasswordHistoryEntity) {
        storedPasswordHistories.add(passwordHistory)
        passwordHistoriesFlow.tryEmit(storedPasswordHistories.toList())
    }

    override fun getPasswordHistoriesForUserAsFlow(
        userId: String,
    ): Flow<List<PasswordHistoryEntity>> {
        return passwordHistoriesFlow
            .map { histories -> histories.filter { it.userId == userId } }
    }

    override suspend fun clearPasswordHistoriesForUser(userId: String) {
        storedPasswordHistories.removeAll { it.userId == userId }
        passwordHistoriesFlow.tryEmit(storedPasswordHistories.toList())
    }
}
