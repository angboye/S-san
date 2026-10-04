package com.example.sscan.domain.usecase

import com.example.sscan.data.repository.ScanRepository
import javax.inject.Inject

class MoveHistoryToTrashUseCase @Inject constructor(
    private val scanRepository: ScanRepository
) {
    suspend operator fun invoke(id: Long) {
        scanRepository.delete(id)
    }
}
