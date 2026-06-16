package com.empresa.vaultdrive.data.api
import com.empresa.vaultdrive.data.model.*
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface OneDriveApi {
    @GET("v1.0/me/drive/items/{itemId}/children")
    suspend fun getChildren(@Header("Authorization") token: String, @Path("itemId") itemId: String, @Query("\$select") select: String = "id,name,folder,file,size,lastModifiedDateTime,@microsoft.graph.downloadUrl,remoteItem", @Query("\$orderby") order: String = "name asc"): Response<DriveItemListResponse>

    @GET("v1.0/me/drive/sharedWithMe")
    suspend fun getSharedWithMe(@Header("Authorization") token: String, @Query("\$select") select: String = "id,name,folder,file,size,lastModifiedDateTime,remoteItem"): Response<DriveItemListResponse>

    @GET("v1.0/drives/{driveId}/items/{itemId}/children")
    suspend fun getChildrenByDrive(@Header("Authorization") token: String, @Path("driveId") driveId: String, @Path("itemId") itemId: String, @Query("\$select") select: String = "id,name,folder,file,size,lastModifiedDateTime,@microsoft.graph.downloadUrl", @Query("\$orderby") order: String = "name asc"): Response<DriveItemListResponse>

    @POST("v1.0/me/drive/items/{parentId}/children")
    suspend fun createFolder(@Header("Authorization") token: String, @Path("parentId") parentId: String, @Body body: CreateFolderRequest): Response<DriveItem>

    @PUT("v1.0/me/drive/items/{parentId}:/{fileName}:/content")
    suspend fun uploadSmall(@Header("Authorization") token: String, @Path("parentId") parentId: String, @Path("fileName", encoded = false) fileName: String, @Body content: RequestBody): Response<DriveItem>

    @POST("v1.0/me/drive/items/{parentId}:/{fileName}:/createUploadSession")
    suspend fun createUploadSession(@Header("Authorization") token: String, @Path("parentId") parentId: String, @Path("fileName", encoded = false) fileName: String, @Body body: UploadSessionRequest): Response<UploadSession>

    companion object {
        fun create(): OneDriveApi = Retrofit.Builder().baseUrl("https://graph.microsoft.com/")
            .client(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(OneDriveApi::class.java)
    }
}
