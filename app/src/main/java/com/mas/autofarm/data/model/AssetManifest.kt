package com.mas.autofarm.data.model

import kotlinx.serialization.Serializable


@Serializable
data class AssetManifest(val files: List<String>)
