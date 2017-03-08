package com.dataintuitive.luciusapi.functions

import com.dataintuitive.luciusapi.binning.BinningFunctions._

import com.dataintuitive.luciuscore.GeneModel.Genes
import com.dataintuitive.luciuscore.Model.DbRow
import com.dataintuitive.luciuscore.SignatureModel.SymbolSignature
import com.dataintuitive.luciuscore.TransformationFunctions._
import com.dataintuitive.luciuscore.ZhangScoreFunctions._
import org.apache.spark.rdd.RDD

import scala.collection.immutable.Map

case class SeqMapStringAny(Result: Seq[Map[String, Any]])

object BinnedZhangFunctions extends Functions {

  type Input = (RDD[DbRow], Genes)
  type Parameters = (Array[String], Int, Int)
  type Output = SeqMapStringAny

  val helpMsg =
    s"""
     """.stripMargin

  def info(data:Input, par:Parameters) = s""

  def header(data:Input, par:Parameters) = s""

  def result(data:Input, par:Parameters) = {

    val (db, genes) = data
    val (rawSignature, binsX, binsY) = par

    // TODO: Make sure we continue with all symbols, or just make the job invalid when it isn't!
    val signature = SymbolSignature(rawSignature)

    val symbolDict = genes.symbol2ProbesetidDict
    val indexDict = genes.index2ProbesetidDict
    val iSignature = signature
      .translate2Probesetid(symbolDict)
      .translate2Index(indexDict)

    val vLength = db.first.sampleAnnotations.t.get.length
    val query = signature2OrderedRankVector(iSignature, vLength)

    // Calculate Zhang score for all entries that contain a rank vector
    // This should be used in a flatMap
    def updateZhang(x:DbRow, query:Array[Double]):Option[(Double, DbRow)] = {
      x.sampleAnnotations.r match {
        case Some(r) => Some((connectionScore(r, query), x))
        case _ => None
      }
    }

    val zhangAdded = db.flatMap{updateZhang(_, query)}
    val zhangStripped = zhangAdded.map(_._1)

    if (binsY > 0)
      SeqMapStringAny(bin2D(zhangStripped, binsX, binsY))
    else
      SeqMapStringAny(bin1D(zhangStripped, binsX))

//    result.map{case (i, z, j, pwid) => Map("indexNew" -> i, "indexOld" -> j, "zhang" -> z, "pwid" -> pwid)}

  }

  def binnedZhang = result _

}