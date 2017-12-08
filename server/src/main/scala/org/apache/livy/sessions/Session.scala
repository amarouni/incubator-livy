/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.livy.sessions

import java.io.{File, InputStream}
import java.net.{URI, URISyntaxException}
import java.security.PrivilegedExceptionAction
import java.util.{Collections, UUID}
import java.util.concurrent.TimeUnit

import scala.concurrent.{ExecutionContext, Future}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.fs.permission.FsPermission
import org.apache.hadoop.security.UserGroupInformation
import org.apache.livy.{LivyConf, Logging, Utils}
import org.apache.livy.utils.AppInfo

import scala.util.Try

object Session {
  trait RecoveryMetadata { val id: Int }

  lazy val configBlackList: Set[String] = {
    val url = getClass.getResource("/spark-blacklist.conf")
    if (url != null) Utils.loadProperties(url).keySet else Set()
  }

  /**
   * Validates and prepares a user-provided configuration for submission.
   *
   * - Verifies that no blacklisted configurations are provided.
   * - Merges file lists in the configuration with the explicit lists provided in the request
   * - Resolve file URIs to make sure they reference the default FS
   * - Verify that file URIs don't reference non-whitelisted local resources
   */
  def prepareConf(
      conf: Map[String, String],
      jars: Seq[String],
      files: Seq[String],
      archives: Seq[String],
      pyFiles: Seq[String],
      livyConf: LivyConf): Map[String, String] = {
    if (conf == null) {
      return Map()
    }

    val errors = conf.keySet.filter(configBlackList.contains)
    if (errors.nonEmpty) {
      throw new IllegalArgumentException(
        "Blacklisted configuration values in session config: " + errors.mkString(", "))
    }

    val confLists: Map[String, Seq[String]] = livyConf.sparkFileLists
      .map { key => (key -> Nil) }.toMap

    val jarFiles: Seq[String] = listFiles(jars, true)
  //  System.err.println("All files: " + filesJars)

   System.err.println("Conf is: " + livyConf.get(LivyConf.DATASTREAMS_DEPENDENCIES_FOLDER))

    val jarFilesFromLocalConf : Seq[String] = {
      if (livyConf.get(LivyConf.DATASTREAMS_DEPENDENCIES_FOLDER)!=null) {
        listFiles("file://"+ livyConf.get(LivyConf.DATASTREAMS_DEPENDENCIES_FOLDER), recursive = true)
      } else {
         Seq[String]()
      }
    }

    val allJarFiles = jarFiles.toSet.union(jarFilesFromLocalConf.toSet)
    System.err.println("Number of dependencies: " + allJarFiles.size)

    val ordinaryFiles: Seq[String] =
      files.map(file => new URI("file", null, new File(new URI(file)).getAbsolutePath, null).toString)

  //  System.err.println("File with correct uri: " + filesWithCorrectUri)

    val detectedCommonFiles: Seq[String] = allJarFiles.toSet.intersect(ordinaryFiles.toSet).toSeq
 //   System.err.println("Detected common files: " + detectedCommonFiles)

  //  System.err.println("File with correct uri: " + filesWithCorrectUri.toSet.diff(detectedCommonFiles.toSet).toSeq)

    val userLists = confLists ++ Map(
      LivyConf.SPARK_JARS -> allJarFiles.toSet.diff(detectedCommonFiles.toSet).toSeq,
      LivyConf.SPARK_FILES -> ordinaryFiles.toSet.diff(detectedCommonFiles.toSet).toSeq,
      LivyConf.SPARK_ARCHIVES -> archives,
      LivyConf.SPARK_PY_FILES -> pyFiles)

    val merged = userLists.flatMap { case (key, list) =>
      val confList = conf.get(key)
        .map { list =>
          resolveURIs(list.split("[, ]+").toSeq, livyConf)
        }
        .getOrElse(Nil)
      val userList = resolveURIs(list, livyConf)
      if (confList.nonEmpty || userList.nonEmpty) {
        Some(key -> (userList ++ confList).mkString(","))
      } else {
        None
      }
    }

    val masterConfList = Map(LivyConf.SPARK_MASTER -> livyConf.sparkMaster()) ++
      livyConf.sparkDeployMode().map(LivyConf.SPARK_DEPLOY_MODE -> _).toMap

    conf ++ masterConfList ++ merged
  }

  /**
   * Prepends the value of the "fs.defaultFS" configuration to any URIs that do not have a
   * scheme. URIs are required to at least be absolute paths.
   *
   * @throws IllegalArgumentException If an invalid URI is found in the given list.
   */
  def resolveURIs(uris: Seq[String], livyConf: LivyConf): Seq[String] = {
    val defaultFS = livyConf.hadoopConf.get("fs.defaultFS").stripSuffix("/")
    uris.filter(_.nonEmpty).map { _uri =>
      val uri = try {
        new URI(_uri)
      } catch {
        case e: URISyntaxException => throw new IllegalArgumentException(e)
      }
      resolveURI(uri, livyConf).toString()
    }
  }

  def resolveURI(uri: URI, livyConf: LivyConf): URI = {
    val defaultFS = livyConf.hadoopConf.get("fs.defaultFS").stripSuffix("/")
    val resolved =
      if (uri.getScheme() == null) {
        require(uri.getPath().startsWith("/"), s"Path '${uri.getPath()}' is not absolute.")
        new URI(defaultFS + uri.getPath())
      } else {
        uri
      }

    if (resolved.getScheme() == "file") {
      // Make sure the location is whitelisted before allowing local files to be added.
      require(livyConf.localFsWhitelist.find(resolved.getPath().startsWith).isDefined,
        s"Local path ${uri.getPath()} cannot be added to user sessions.")
    }

    resolved
  }

  def listFiles(paths : Seq[String], recursive: Boolean): Seq[String] = {
    paths.flatMap(listFiles(_, recursive))
  }

  def listFiles(base: String, recursive: Boolean): Seq[String] = {
    val allFiles = listFiles(new File(new URI(base)), recursive)
    allFiles
      .map(x => new URI("file", null, x.getAbsolutePath, null).toString)
  }

  def listFiles(base: File, recursive: Boolean): Seq[File] = {
    val files = base.listFiles
    // TODO: use scala-esque code, avoid nulls
    if(files != null) {
      val result = files.filter(_.isFile)
      result ++
        files
          .filter(_.isDirectory)
          .filter(_ => recursive)
          .flatMap(listFiles(_, recursive))
    } else {
      Seq(base)
    }
  }
}

abstract class Session(val id: Int, val owner: String, val livyConf: LivyConf)
  extends Logging {

  import Session._

  protected implicit val executionContext = ExecutionContext.global

  protected var _appId: Option[String] = None

  private var _lastActivity = System.nanoTime()

  // Directory where the session's staging files are created. The directory is only accessible
  // to the session's effective user.
  private var stagingDir: Path = null

  def appId: Option[String] = _appId

  var appInfo: AppInfo = AppInfo()

  def lastActivity: Long = state match {
    case SessionState.Error(time) => time
    case SessionState.Dead(time) => time
    case SessionState.Success(time) => time
    case _ => _lastActivity
  }

  def logLines(): IndexedSeq[String]

  def recordActivity(): Unit = {
    _lastActivity = System.nanoTime()
  }

  def recoveryMetadata: RecoveryMetadata

  def state: SessionState

  def stop(): Future[Unit] = Future {
    try {
      info(s"Stopping $this...")
      stopSession()
      info(s"Stopped $this.")
    } catch {
      case e: Exception =>
        warn(s"Error stopping session $id.", e)
    }

    try {
      if (stagingDir != null) {
        debug(s"Deleting session $id staging directory $stagingDir")
        doAsOwner {
          val fs = FileSystem.newInstance(livyConf.hadoopConf)
          try {
            fs.delete(stagingDir, true)
          } finally {
            fs.close()
          }
        }
      }
    } catch {
      case e: Exception =>
        warn(s"Error cleaning up session $id staging dir.", e)
    }
  }


  override def toString(): String = s"${this.getClass.getSimpleName} $id"

  protected def stopSession(): Unit

  protected val proxyUser: Option[String]

  protected def doAsOwner[T](fn: => T): T = {
    val user = proxyUser.getOrElse(owner)
    if (user != null) {
      val ugi = if (UserGroupInformation.isSecurityEnabled) {
        if (livyConf.getBoolean(LivyConf.IMPERSONATION_ENABLED)) {
          UserGroupInformation.createProxyUser(user, UserGroupInformation.getCurrentUser())
        } else {
          UserGroupInformation.getCurrentUser()
        }
      } else {
        UserGroupInformation.createRemoteUser(user)
      }
      ugi.doAs(new PrivilegedExceptionAction[T] {
        override def run(): T = fn
      })
    } else {
      fn
    }
  }

  protected def copyResourceToHDFS(dataStream: InputStream, name: String): URI = doAsOwner {
    val fs = FileSystem.newInstance(livyConf.hadoopConf)

    try {
      val filePath = new Path(getStagingDir(fs), name)
      debug(s"Uploading user file to $filePath")

      val outFile = fs.create(filePath, true)
      val buffer = new Array[Byte](512 * 1024)
      var read = -1
      try {
        while ({read = dataStream.read(buffer); read != -1}) {
          outFile.write(buffer, 0, read)
        }
      } finally {
        outFile.close()
      }
      filePath.toUri
    } finally {
      fs.close()
    }
  }

  private def getStagingDir(fs: FileSystem): Path = synchronized {
    if (stagingDir == null) {
      val stagingRoot = Option(livyConf.get(LivyConf.SESSION_STAGING_DIR)).getOrElse {
        new Path(fs.getHomeDirectory(), ".livy-sessions").toString()
      }

      val sessionDir = new Path(stagingRoot, UUID.randomUUID().toString())
      fs.mkdirs(sessionDir)
      fs.setPermission(sessionDir, new FsPermission("700"))
      stagingDir = sessionDir
      debug(s"Session $id staging directory is $stagingDir")
    }
    stagingDir
  }

}
