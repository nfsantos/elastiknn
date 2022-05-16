package com.klibisz.elastiknn.benchmarks

import com.klibisz.elastiknn.api._
import zio._
import zio.console.Console

object LocalBenchmark extends App {

  private val field = "vec"
  private val bucket = s"elastiknn-benchmarks"
  private val k = 100

  private val experiments =
    Seq(1).flatMap { shards =>
      Seq(
        Experiment(
          Dataset.AnnbFashionMnist,
          Mapping.DenseFloat(Dataset.AnnbFashionMnist.dims),
          Seq(Query(NearestNeighborsQuery.Exact(field, Similarity.L2), k)),
          shards = shards
        ),
        Experiment(
          Dataset.AnnbFashionMnist,
          Mapping.L2Lsh(Dataset.AnnbFashionMnist.dims, 75, 4, 7),
          Seq(
            Query(NearestNeighborsQuery.L2Lsh(field, 1000 / shards, 0), k),
            Query(NearestNeighborsQuery.L2Lsh(field, 2000 / shards, 3), k)
          ),
          shards = shards
        )
//        ,
//        Experiment(
//          Dataset.AnnbSift,
//          Mapping.L2Lsh(Dataset.AnnbSift.dims, 100, 4, 2),
//          Seq(Query(NearestNeighborsQuery.L2Lsh(field, 5000 / shards, 0), k)),
//          shards = shards
//        )
      )
    }

  override def run(args: List[String]): URIO[Console, ExitCode] = {
    val s3Url = "http://localhost:9000"
    val s3Client = S3Utils.client(Some(s3Url))
    val experimentEffects = experiments.map { exp =>
      val key = s"experiments/${exp.uuid}"
      for {
        _ <- ZIO(s3Client.putObject(bucket, key, codecs.experimentCodec(exp).noSpaces))
        _ <- Execute(
          Execute.Params(
            experimentKey = key,
            datasetsPrefix = "data/processed",
            resultsPrefix = "results",
            bucket = bucket,
            s3Url = Some(s3Url)
          )
        )
      } yield ()
    }
    val pipeline = for {
      bucketExists <- ZIO(s3Client.doesBucketExistV2(bucket))
      _ <- if (!bucketExists) ZIO(s3Client.createBucket(bucket)) else ZIO.succeed(())
      _ <- ZIO.collectAll(experimentEffects)
      _ <- Aggregate(
        Aggregate.Params(
          "results",
          "results/aggregate.csv",
          bucket,
          Some(s3Url)
        )
      )
    } yield ()
    pipeline.exitCode
  }

}
