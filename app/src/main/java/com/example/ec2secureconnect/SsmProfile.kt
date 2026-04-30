package com.example.ec2secureconnect

data class SsmProfile(
    val id: String,
    val name: String,
    val accessKey: String,
    val secretAccessKey: String,
    val region: String,
    val instanceId: String,
    val remotePort: Int,
    val localPort: Int
)
