package com.deskcubby.app.data.model

const val DEFAULT_CLOUD_SYNC_USER_AGENT = "DeskCubby-Sync/1"

/**
 * Persisted cloud-sync settings.
 *
 * WebDAV passwords are supplied by the device-local credential store. S3 credentials are persisted
 * in the app's private DataStore so the editor can show them again, but neither kind of credential
 * is exported in DeskCubby JSON backups. [toString] stays redacted to prevent accidental logging.
 */
data class CloudSyncConfig(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val serviceType: CloudSyncServiceType = CloudSyncServiceType.WEBDAV,
    val endpointUrl: String = "",
    val remotePath: String = "DeskCubby",
    val userAgent: String = DEFAULT_CLOUD_SYNC_USER_AGENT,
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val s3Bucket: String = "",
    val s3Region: String = "us-east-1",
    val s3AccessKey: String = "",
    val s3SecretKey: String = "",
    val s3SessionToken: String = "",
    val s3PathStyle: Boolean = true,
    val allowInsecureHttp: Boolean = false,
    val selectedContents: Set<CloudSyncContent> = setOf(
        CloudSyncContent.DIARIES,
        CloudSyncContent.MEDIA,
        CloudSyncContent.JSON_BACKUP,
        CloudSyncContent.USAGE_STATISTICS,
        CloudSyncContent.READING_PROGRESS,
    ),
    val direction: CloudSyncDirection = CloudSyncDirection.TWO_WAY,
) {
    override fun toString(): String = buildString {
        append("CloudSyncConfig(id=")
        append(id)
        append(", name=")
        append(name)
        append(", enabled=")
        append(enabled)
        append(", serviceType=")
        append(serviceType)
        append(", endpointUrl=")
        append(if (endpointUrl.isBlank()) "" else "<configured>")
        append(", remotePath=")
        append(remotePath)
        append(", userAgent=")
        append(userAgent)
        append(", webDavUsername=")
        append(if (webDavUsername.isBlank()) "" else "<redacted>")
        append(", webDavPassword=<redacted>, s3Bucket=")
        append(s3Bucket)
        append(", s3Region=")
        append(s3Region)
        append(", s3AccessKey=")
        append(if (s3AccessKey.isBlank()) "" else "<redacted>")
        append(", s3SecretKey=<redacted>, s3SessionToken=<redacted>")
        append(", s3PathStyle=")
        append(s3PathStyle)
        append(", allowInsecureHttp=")
        append(allowInsecureHttp)
        append(", selectedContents=")
        append(selectedContents)
        append(", direction=")
        append(direction)
        append(')')
    }
}

enum class CloudSyncServiceType {
    WEBDAV,
    S3_COMPATIBLE,
}

enum class CloudSyncContent(
    val remoteDirectory: String,
) {
    DIARIES("diaries"),
    MEDIA("media"),
    JSON_BACKUP("json"),
    USAGE_STATISTICS("usage/v1"),
    READING_PROGRESS("reading/v1"),
}

enum class CloudSyncDirection {
    UPLOAD_ONLY,
    TWO_WAY,
}
