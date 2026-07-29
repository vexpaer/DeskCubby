package com.deskcubby.app.data.model

/**
 * Persisted cloud-sync settings.
 *
 * Passwords and access keys are runtime fields used by the transport. They must be supplied by a
 * device-local credential store and must not be exported in DeskCubby JSON backups. [toString] is
 * overridden because data-class generated output would otherwise expose credentials to crash
 * reports or accidental logs.
 */
data class CloudSyncConfig(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val serviceType: CloudSyncServiceType = CloudSyncServiceType.WEBDAV,
    val endpointUrl: String = "",
    val remotePath: String = "DeskCubby",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val s3Bucket: String = "",
    val s3Region: String = "us-east-1",
    val s3AccessKey: String = "",
    val s3SecretKey: String = "",
    val s3SessionToken: String = "",
    val allowInsecureHttp: Boolean = false,
    val selectedContents: Set<CloudSyncContent> = setOf(
        CloudSyncContent.DIARIES,
        CloudSyncContent.MEDIA,
        CloudSyncContent.JSON_BACKUP,
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
        append(", webDavUsername=")
        append(if (webDavUsername.isBlank()) "" else "<redacted>")
        append(", webDavPassword=<redacted>, s3Bucket=")
        append(s3Bucket)
        append(", s3Region=")
        append(s3Region)
        append(", s3AccessKey=")
        append(if (s3AccessKey.isBlank()) "" else "<redacted>")
        append(", s3SecretKey=<redacted>, s3SessionToken=<redacted>")
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
}

enum class CloudSyncDirection {
    UPLOAD_ONLY,
    TWO_WAY,
}
