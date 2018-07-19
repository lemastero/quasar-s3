/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.s3

import quasar.Data
import quasar.api.ResourceError
import quasar.api.ResourceError.{CommonError, ReadError}
import quasar.api.ResourcePath.{Leaf, Root}
import quasar.api.datasource.DatasourceType
import quasar.api.{ResourceName, ResourcePath, ResourcePathType}
import quasar.connector.datasource.LightweightDatasource
import quasar.contrib.cats.effect._
import quasar.contrib.pathy.APath

import scala.concurrent.ExecutionContext
import slamdata.Predef.{Stream => _, _}

import cats.arrow.FunctionK
import cats.effect.{Effect, Async}
import fs2.Stream
import org.http4s.Uri
import org.http4s.client.Client
import pathy.Path
import pathy.Path.{DirName, FileName}
import scalaz.syntax.applicative._
import scalaz.syntax.either._
import scalaz.{\/, \/-, -\/}
import shims._

final class S3DataSource[F[_]: Effect, G[_]: Async] (
  client: Client[F],
  bucket: Uri,
  s3JsonParsing: S3JsonParsing)(ec: ExecutionContext)
    extends LightweightDatasource[F, Stream[G, ?], Stream[G, Data]] {

  def kind: DatasourceType = s3.datasourceKind

  val shutdown: F[Unit] = client.shutdown

  def evaluate(path: ResourcePath): F[ReadError \/ Stream[G, Data]] =
    path match {
      case Root => ResourceError.notAResource(path).left.point[F]
      case Leaf(file) => impl.evaluate[F](s3JsonParsing, client, bucket, file) map {
        case None => (ResourceError.pathNotFound(Leaf(file)): ReadError).left
        /* In http4s, the type of streaming results is the same as
         every other effectful operation. However,
         LightweightDataSourceModule forces us to separate the types,
         so we need to translate */
        case Some(s) => s.translate[G](FToG).right[ReadError]
      }
    }

  def children(path: ResourcePath): F[CommonError \/ Stream[G, (ResourceName, ResourcePathType)]] = {
    impl.children(client, bucket, dropEmpty(path.toPath)) map {
      case None =>
        ResourceError.pathNotFound(path).left[Stream[G, (ResourceName, ResourcePathType)]]
      case Some(paths) =>
        Stream.emits(paths.toList.map {
          case -\/(Path.DirName(dn)) => (ResourceName(dn), ResourcePathType.ResourcePrefix)
          case \/-(Path.FileName(fn)) => (ResourceName(fn), ResourcePathType.Resource)
        }).covary[G].right[CommonError]
    }
  }

  def isResource(path: ResourcePath): F[Boolean] = path match {
    case Root => false.pure[F]
    case Leaf(file) => Path.refineType(dropEmpty(file)) match {
      case -\/(_) => false.pure[F]
      case \/-(f) => impl.isResource(client, bucket, f)
    }
  }

  private def dropEmpty(path: APath): APath =
    Path.peel(path) match {
      case Some((d, \/-(FileName(fn)))) if fn.isEmpty => d
      case Some((d, -\/(DirName(dn)))) if dn.isEmpty => d
      case Some(_) => path
      case None => path
    }

  private val FToG: FunctionK[F, G] = new FunctionK[F, G] {
    implicit def ec0: ExecutionContext = ec

    def apply[A](fa: F[A]): G[A] = fa.to[G]
  }
}