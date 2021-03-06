package com.dataintuitive.luciusapi.functions

import com.dataintuitive.luciuscore.GeneModel.Genes
import com.dataintuitive.luciuscore.Model.DbRow
import com.dataintuitive.luciuscore.SignatureModel.SymbolSignature
import com.dataintuitive.luciuscore.TransformationFunctions._
import com.dataintuitive.luciuscore.ZhangScoreFunctions._
import org.apache.spark.rdd.RDD

import scala.collection.immutable.Map

object AnnotatedplatewellidsFunctions extends Functions {

  type Input = (RDD[DbRow], Genes)
  type Parameters = (String, Int, List[String], List[String], List[String])
  type Output = ArrayMapStringAny
  
  val helpMsg =
    s"""Returns a table with annotations about platewellids/samples, optionally with zhang score.
        |
     | Input:
        |
     | - __`query`__: signature or gene list for calculating Zhang scores (optional, no score is calculated if not provided)
        |
     | - __`features`__: list of features to return with (optional, all features are returned if not provided)
        |
     | - __`pwids`__: list of pwids to return annotations for (optional, some - see limit - pwids are returned)
        |
     | - __`limit`__: number of pwids to return if none are selected explicitly (optional, default is 10)
        |
     | - __`version`: "v1" or "v2" (optional, default is v1)
        |
     """.stripMargin

  type ScoredDbRow = (Double, DbRow)

  val ZHANG = Set("zhang", "similarity", "Zhang", "Similarity")
  val PWID =  Set("id", "pwid")
  val JNJS = Set("jnjs", "Jnjs")
  val JNJB = Set("jnjb", "Jnjb")
  val SMILES = Set("Smiles", "smiles", "SMILES")
  val INCHIKEY = Set("inchikey", "Inchikey")
  val COMPOUNDNAME = Set("compoundname", "CompoundName", "Compoundname", "name", "Name")
  val TYPE = Set("Type", "type")
  val BATCH = Set("batch", "Batch")
  val PLATEID = Set("plateid", "PlateId")
  val WELL = Set("well", "Well")
  val PROTOCOLNAME = Set("protocolname", "cellline", "CellLine", "ProtocolName")
  val CONCENTRATION = Set("concentration", "Concentration")
  val YEAR = Set("year", "Year")
  val TARGETS = Set("targets", "knownTargets", "Targets")

  def featureByLens[T](lens:ScoredDbRow => T)(r:ScoredDbRow) = lens(r)

  val extractZhang = featureByLens(_._1) _
  val extractPwid  = featureByLens(_._2.pwid.getOrElse("No PWID")) _

  val extractJnjs = featureByLens(_._2.compoundAnnotations.compound.jnjs.getOrElse("No Jnjs")) _
  val extractJnjb = featureByLens(_._2.compoundAnnotations.compound.jnjb.getOrElse("No Jnjb")) _
  val extractSmiles = featureByLens(_._2.compoundAnnotations.compound.smiles.getOrElse("No Smiles")) _
  val extractInchikey = featureByLens(_._2.compoundAnnotations.compound.inchikey.getOrElse("No Inchikey")) _
  val extractCompoundname = featureByLens(_._2.compoundAnnotations.compound.name.getOrElse("No Compound Name")) _
  val extractType = featureByLens(_._2.compoundAnnotations.compound.ctype.getOrElse("No Compound Type")) _

  val extractBatch = featureByLens(_._2.sampleAnnotations.sample.batch.getOrElse("No Batch id")) _
  val extractPlateid = featureByLens(_._2.sampleAnnotations.sample.plateid.getOrElse("No Plate id")) _
  val extractWell = featureByLens(_._2.sampleAnnotations.sample.well.getOrElse("No Well id")) _
  val extractProtocolname = featureByLens(_._2.sampleAnnotations.sample.protocolname.getOrElse("No Protocol")) _
  val extractConcentration = featureByLens(_._2.sampleAnnotations.sample.concentration.getOrElse("No Concentration")) _
  val extractYear = featureByLens(_._2.sampleAnnotations.sample.year.getOrElse("No Year")) _

  val extractTargets = featureByLens(_._2.compoundAnnotations.getKnownTargets.toList) _

  def extractFeatures(r:ScoredDbRow, features:List[String]) = features.map{ _ match {
    case x if ZHANG contains x => extractZhang(r)
    case x if PWID contains x => extractPwid(r)
    case x if JNJS contains x => extractJnjs(r)
    case x if JNJB contains x => extractJnjb(r)
    case x if SMILES contains x => extractSmiles(r)
    case x if INCHIKEY contains x => extractInchikey(r)
    case x if COMPOUNDNAME contains x => extractCompoundname(r)
    case x if TYPE contains x => extractType(r)
    case x if BATCH contains x => extractBatch(r)
    case x if PLATEID contains x => extractPlateid(r)
    case x if WELL contains x => extractWell(r)
    case x if PROTOCOLNAME contains x => extractProtocolname(r)
    case x if CONCENTRATION contains x => extractConcentration(r)
    case x if YEAR contains x => extractYear(r)
    case x if TARGETS contains x => extractTargets(r)
    case _ => "Feature not found"
  }}


  def info(data:Input, par:Parameters) = s"Annotations for a list of samples"

  def header(data:Input, par:Parameters) = "Selected features"

  def result(data:Input, par:Parameters) = {

    val (db, genes) = data
    val (version, limit, signatureQuery, pwidsQuery, featuresQuery) = par

    val signatureSpecified = !(signatureQuery.headOption.getOrElse(".*") == ".*")
    val pwidsSpecified = !(pwidsQuery.headOption.getOrElse(".*") == ".*")
    val featuresSpecified = !(featuresQuery.headOption.getOrElse(".*") == ".*")

    // TODO: Make sure we continue with all symbols, or just make the job invalid when it isn't!
    val signature = SymbolSignature(signatureQuery.toArray)
    val symbolDict = genes.symbol2ProbesetidDict
    val indexDict = genes.index2ProbesetidDict
    val iSignature = signature
      .translate2Probesetid(symbolDict)
      .translate2Index(indexDict)

    // Just taking the first element t vector is incorrect, what do we use options for after all?!
    // So... a version of takeWhile to the rescue
    val vLength = db.filter(_.sampleAnnotations.t.isDefined).first.sampleAnnotations.t.get.length
    val query = signature2OrderedRankVector(iSignature, vLength)

    // Calculate Zhang score for all entries that contain a rank vector
    // This should be used in a flatMap
    def updateZhang(x:DbRow, query:Array[Double]):Option[(Double, DbRow)] = {
      x.sampleAnnotations.r match {
        case Some(r) => Some((connectionScore(r, query), x))
        case _ => None
      }
    }

    // Filter the pwids in the query, at least if one is specified
    val filteredDb =
    if (pwidsSpecified)
      db.filter(sample =>
        pwidsQuery.map(pwid => sample.pwid.exists(_.matches(pwid))).reduce(_||_))
    else
      db

    // Add Zhang score if signature is present
    val zhangAdded:RDD[(Double, DbRow)] =
    if (signatureSpecified)
      filteredDb.flatMap{updateZhang(_, query)}
    else
      filteredDb.map((0.0, _))

    // Should the output be limited? Only if no pwids or filter are specified
    val limitOutput:Boolean = !pwidsSpecified && (filteredDb.count > limit)

    val collected =
      if (!limitOutput)
        zhangAdded.collect
      else
        zhangAdded.take(limit)

    // Create a feature selection, depending on the input
    // TODO: Add target information and make sure it gets parsed correctly!
    val features = {
      if (featuresSpecified) featuresQuery
      else List("zhang","id","jnjs", "jnjb", "smiles", "inchikey",
        "compoundname", "Type", "targets", "batch",
        "plateid", "well", "protocolname", "concentration", "year")
    }

    val result = collected
      .sortBy{case (z, x) => -z}
      .map(entry => extractFeatures(entry, features))

    ArrayMapStringAny(result.map(_.zip(features).map(_.swap).toMap))

  }

  def annotatedplatewellids = result _

}