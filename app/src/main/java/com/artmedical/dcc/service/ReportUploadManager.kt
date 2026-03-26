package com.artmedical.dcc.service

import android.content.Context
import android.util.Log
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferNetworkLossHandler
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.artmedical.dcc.BuildConfig
import com.artmedical.dcc.service.data.ReportDao
import com.artmedical.dcc.service.data.ReportEntity
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ReportUploadManager(
    context: Context,
    credentialsProvider: CognitoCachingCredentialsProvider,
    private val reportDao: ReportDao
) {
    private val tag = "DCC-ReportUpload"
    private val bucketName = BuildConfig.S3_REPORTS_BUCKET
    private val transferUtility: TransferUtility

    init {
        TransferNetworkLossHandler.getInstance(context)

        val s3Client = AmazonS3Client(credentialsProvider, Region.getRegion(Regions.US_EAST_1))
        transferUtility = TransferUtility.builder()
            .context(context)
            .s3Client(s3Client)
            .build()
    }

    suspend fun uploadToS3(report: ReportEntity): Boolean {
        val file = File(report.localFilePath)
        if (!file.exists()) {
            Log.e(tag, "Local file not found: ${report.localFilePath}")
            reportDao.updateStatus(report.reportId, "FAILED", "Local file not found")
            return false
        }

        reportDao.setStatus(report.reportId, "UPLOADING")

        return suspendCancellableCoroutine { continuation ->
            try {
                val observer = transferUtility.upload(
                    bucketName,
                    report.s3Key,
                    file
                )

                observer.setTransferListener(object : TransferListener {
                    override fun onStateChanged(id: Int, state: TransferState) {
                        when (state) {
                            TransferState.COMPLETED -> {
                                Log.i(tag, "S3 upload completed: ${report.s3Key}")
                                if (continuation.isActive) continuation.resume(true)
                            }
                            TransferState.FAILED -> {
                                Log.e(tag, "S3 upload failed: ${report.s3Key}")
                                if (continuation.isActive) continuation.resume(false)
                            }
                            TransferState.CANCELED -> {
                                Log.w(tag, "S3 upload canceled: ${report.s3Key}")
                                if (continuation.isActive) continuation.resume(false)
                            }
                            else -> Log.d(tag, "S3 upload state: $state for ${report.s3Key}")
                        }
                    }

                    override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                        Log.v(tag, "Upload progress: $bytesCurrent / $bytesTotal")
                    }

                    override fun onError(id: Int, ex: Exception) {
                        Log.e(tag, "S3 upload error", ex)
                        if (continuation.isActive) continuation.resume(false)
                    }
                })

                continuation.invokeOnCancellation {
                    observer.cleanTransferListener()
                    transferUtility.cancel(observer.id)
                }
            } catch (e: Exception) {
                Log.e(tag, "Exception starting S3 upload", e)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    companion object {
        fun computeS3Key(deviceSerial: String, reportDate: String, reportId: String): String {
            return "reports/$deviceSerial/$reportDate/$reportId.pdf"
        }
    }
}
