package com.github.stepanort.colorich.immich.model

import kotlinx.serialization.Serializable

@Serializable
data class AssetDto(
    val id: String = "",
    val originalFileName: String? = null,
    val fileCreatedAt: String? = null,
    val type: String? = null
)

@Serializable
data class ActivityUserDto(
    val id: String? = null,
    val name: String? = null,
    val email: String? = null
)

@Serializable
data class ActivityDto(
    val id: String = "",
    val assetId: String? = null,
    val albumId: String? = null,
    val comment: String? = null,
    val type: String = "comment",
    val createdAt: String? = null,
    val user: ActivityUserDto? = null
)

@Serializable
data class CreateActivityDto(
    val albumId: String,
    val assetId: String? = null,
    val comment: String,
    val type: String = "comment"
)

@Serializable
data class MetadataSearchDto(
    val albumIds: List<String> = emptyList(),
    val page: Int = 1,
    val size: Int = 250
)

@Serializable
data class SearchAssetsResponseDto(
    val items: List<AssetDto> = emptyList(),
    val total: Int = 0,
    val nextPage: String? = null
)

@Serializable
data class SearchResultDto(
    val assets: SearchAssetsResponseDto = SearchAssetsResponseDto()
)

@Serializable
data class UploadAssetResponseDto(
    val id: String = "",
    val duplicate: Boolean = false
)

@Serializable
data class BulkIdsDto(
    val ids: List<String>
)
