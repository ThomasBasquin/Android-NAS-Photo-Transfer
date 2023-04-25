package com.example.phototonas

import android.annotation.SuppressLint
import android.os.AsyncTask
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.util.EnumSet
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils.toEnumSet
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.File as SmbFile
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.Share
import androidx.exifinterface.media.ExifInterface
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale


class MainActivity : AppCompatActivity(){

    @SuppressLint("Range")
    private fun getAllPhotosFromNestor(): List<File> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = MediaStore.Images.Media.DATA + " LIKE ?"
        val selectionArgs = arrayOf("%/Nestor/%")
        val cursor = contentResolver.query(uri, null, selection, selectionArgs, null)
        val photoFiles = mutableListOf<File>()

        if (cursor != null) {
            while (cursor.moveToNext()) {
                val path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA))
                photoFiles.add(File(path))
            }
            cursor.close()
        }
        return photoFiles
    }


    class ConnectToNasTask(
        private val username: String,
        private val password: String,
        private val domain: String,
        private val onConnected: (SMBClient) -> Unit
    ) : AsyncTask<Void, Void, SMBClient>() {

        override fun doInBackground(vararg params: Void?): SMBClient {
            val config = SmbConfig.builder()
                .withMultiProtocolNegotiate(true)
                .build()

            val client = SMBClient(config)
            val address = InetAddress.getByName("192.168.1.10")
            val auth = AuthenticationContext(username, password.toCharArray(), domain)

            val connection = client.connect(address.toString())
            val session = connection.authenticate(auth)

            return client
        }

        override fun onPostExecute(result: SMBClient) {
            super.onPostExecute(result)
            onConnected(result)
        }
    }






    private fun transferPhotosAndRemoveDuplicates(nasClient: SMBClient, photoFiles: List<File>) {
        val connection = nasClient.connect(InetAddress.getByName("192.168.1.10").toString())
        val session = connection.authenticate(AuthenticationContext("admin", "Felmdam!".toCharArray(), "domain"))

        val remoteShare = session.connectShare("Nestor") as DiskShare

        // Lire les chemins des photos déjà traitées
        val processedPhotoPaths = readProcessedPhotos(remoteShare)

        for (photoFile in photoFiles) {
            val remotePath = buildRemotePath(photoFile)

            // Vérifier si la photo a déjà été traitée
            if (!processedPhotoPaths.contains(remotePath) && !remoteShare.fileExists(remotePath)) {
                val remoteFile = remoteShare.openFile(
                    remotePath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null
                ) as SmbFile

                FileInputStream(photoFile).use { inputStream ->
                    val buffer = ByteArray(1024)
                    var bytesRead = inputStream.read(buffer)

                    val outputStream = remoteFile.outputStream

                    while (bytesRead != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesRead = inputStream.read(buffer)
                    }
                }
                remoteFile.close()

                // Ajouter le chemin de la photo traitée au fichier sur le NAS
                addProcessedPhoto(remoteShare, remotePath)
            }
        }
    }


    private fun buildRemotePath(photoFile: File): String {
        val exifInterface = ExifInterface(photoFile.absolutePath)
        val dateString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)

        if (dateString != null) {
            val datePattern = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
            val date = datePattern.parse(dateString)

            if (date != null) {
                val calendar = Calendar.getInstance()
                calendar.time = date
                val year = calendar.get(Calendar.YEAR)
                val month = calendar.get(Calendar.MONTH) + 1

                return "photo\\$year\\$month\\${photoFile.name}"
            }
        }

        // Si les métadonnées EXIF ne sont pas disponibles, utilisez la date de modification du fichier
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = photoFile.lastModified()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1

        return "photo\\$year\\$month\\${photoFile.name}"
    }

    private fun addProcessedPhoto(remoteShare: DiskShare, photoPath: String) {
        val processedPhotosFile = "photo\\processed_photos.txt"

        remoteShare.openFile(
            processedPhotosFile,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        ).use { remoteFile ->
            remoteFile.outputStream.writer().append(photoPath).append("\n").flush()
        }
    }


    private fun readProcessedPhotos(remoteShare: DiskShare): Set<String> {
        val processedPhotosFile = "photo\\processed_photos.txt"
        val processedPhotoPaths = mutableSetOf<String>()

        if (remoteShare.fileExists(processedPhotosFile)) {
            remoteShare.openFile(
                processedPhotosFile,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { remoteFile ->
                remoteFile.inputStream.reader().useLines { lines ->
                    lines.forEach { line ->
                        processedPhotoPaths.add(line)
                    }
                }
            }
        }

        return processedPhotoPaths
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnTransfer = findViewById<Button>(R.id.btnTransfer)
        btnTransfer.setOnClickListener {
            val photoFiles = getAllPhotosFromNestor()
            ConnectToNasTask("admin", "Felmdam!", "", onConnected = { nasClient ->
                transferPhotosAndRemoveDuplicates(nasClient, photoFiles)
                nasClient.close()
            }).execute()
        }
    }




}