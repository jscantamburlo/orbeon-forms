/**
 * Copyright (C) 2014 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr

import org.orbeon.oxf.fr.Names._
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.xforms.action.XFormsAPI
import org.orbeon.oxf.xforms.analysis.controls.{LHHA, StaticLHHASupport}
import org.orbeon.oxf.xforms.control._
import org.orbeon.oxf.xforms.control.controls.{XFormsOutputControl, XFormsSelect1Control, XFormsSelectControl}
import org.orbeon.oxf.xforms.function.xxforms.XXFormsItemset
import org.orbeon.oxf.xforms.itemset.Item
import org.orbeon.oxf.xforms.model.XFormsInstance
import org.orbeon.oxf.xforms.submission.SubmissionUtils
import org.orbeon.oxf.xforms.{XFormsContainingDocument, itemset}
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.NodeConversions._
import org.orbeon.scaxon.SimplePath._
import org.orbeon.xbl.ErrorSummary

import scala.xml.Elem

object FormRunnerMetadata {

  case class ControlDetails(
    name         : String,
    typ          : String,
    level        : Int,
    datatype     : Option[String],
    lhhaAndItems : List[(Lang, (List[(LHHA, String)], List[Item]))],
    value        : Option[ControlValue],
    forHashes    : List[String]
  )

  case class Lang(lang: String) extends AnyVal

  sealed abstract class ControlValue { val storageValue: String }
  case class SingleControlValue(storageValue: String, formattedValue: Option[String]) extends ControlValue
  case class MultipleControlValue(storageValue: String, formattedValues: List[String]) extends ControlValue

  private val Debug            = false

  // TODO: Localize, must come from resources
  val NotAvailableString       = "N/A"

  val ControlsToIgnore         = Set("image", "image-attachment", "attachment", "trigger", "handwritten-signature")

  val SelectedCheckboxString   = "☒"
  val DeselectedCheckboxString = "☐"

  //@XPathFunction
  def findAllControlsWithValues(html: Boolean): String = {

    val controlDetails = createFormMetadataDocument2(XFormsAPI.inScopeContainingDocument)

    def createLine(control: ControlDetails, isFirst: Boolean): Option[String] =
      control match { case ControlDetails(name, typ, level, _, lhhaAndItems, value, _) ⇒

        val (lhhas, _) = lhhaAndItems.head._2 // TODO: use current/requested lang

        // TODO: escape values when in HTML

        val lhhasMap = lhhas.toMap

        val valueOpt =
          value flatMap {
            case SingleControlValue  (_, formattedValue)  ⇒
              formattedValue
            case MultipleControlValue(_, formattedValues) ⇒
              formattedValues.nonEmpty option (formattedValues map (SelectedCheckboxString + ' ' + _) mkString ", ")
          }

        def combineLabelAndValue(label: String, value: Option[String]): Option[String] = {

          val normalizedLabel =
            label.trimAllToOpt map { l ⇒
              if (l.endsWith(":")) l.init else l
            }

          val normalizedValue =
            value map (_.trimAllToOpt getOrElse NotAvailableString)

          val list = normalizedLabel.toList ::: normalizedValue.toList

          list.nonEmpty option (list mkString ": ")
        }

        val r =
          typ match {
            case "section" ⇒ lhhasMap.get(LHHA.Label)
            case _         ⇒ combineLabelAndValue(lhhasMap(LHHA.Label), valueOpt)
          }

          r map (_ + (if (Debug) s" [$name/$typ]" else ""))
      }

    val sb = new StringBuilder

    def processNext(it: List[ControlDetails], level: Int): Unit =
      it match {
        case Nil ⇒
        case sectionControl :: rest if sectionControl.typ == "section" ⇒

          val sectionLevel = sectionControl.level
          val nextLevel    = level + 1

          createLine(sectionControl, isFirst = false) foreach { line ⇒
            sb ++= s"<h$nextLevel>"
            sb ++= line
            sb ++= s"</h$nextLevel>"
          }

          sb ++= "<ul>"
          processNext(rest.takeWhile(_.level > sectionLevel), nextLevel)
          sb ++= "</ul>"
          processNext(rest.dropWhile(_.level > sectionLevel), level)
        case ignoredControl :: rest if ControlsToIgnore(ignoredControl.typ) ⇒
          processNext(rest, level)
        case control :: rest ⇒
          createLine(control, isFirst = false) foreach { line ⇒
            sb ++= "<li>"
            sb ++= line
            sb ++= "</li>"
          }
          processNext(rest, level)
      }

    processNext(controlDetails, 1)

    if (html)
      sb.toString()
    else
      sb.toString()
  }

  //@XPathFunction
  def createFormMetadataDocument: NodeInfo = {

    val doc = XFormsAPI.inScopeContainingDocument

    val controls = doc.getControls.getCurrentControlTree.effectiveIdsToControls

    def instanceInScope(control: XFormsSingleNodeControl, staticId: String): Option[XFormsInstance] =
      control.container.resolveObjectByIdInScope(control.getEffectiveId, staticId, None) flatMap
        collectByErasedType[XFormsInstance]

    def resourcesInstance(control: XFormsSingleNodeControl): Option[XFormsInstance] =
      instanceInScope(control, FormResources)

    def isBoundToFormDataInScope(control: XFormsSingleNodeControl): Boolean = {

      val boundNode = control.boundNode
      val data      = instanceInScope(control, FormInstance)

      (boundNode map (_.getDocumentRoot)) == (data map (_.root))
    }

    def iterateResources(resourcesInstance: XFormsInstance): Iterator[(String, NodeInfo)] =
      for (resource ← resourcesInstance.rootElement / Resource iterator)
      yield
        resource.attValue("*:lang") → resource

    def resourcesForControl(staticControl: StaticLHHASupport, lang: String, resourcesRoot: NodeInfo, controlName: String) = {

      val enclosingHolder = resourcesRoot descendant controlName take 1

      val lhhas =
        for {
          lhha     ← LHHA.values.to[List]
          if staticControl.hasLHHA(lhha)
          lhhaName = lhha.entryName
          holder   ← enclosingHolder child lhhaName
        } yield
          <dummy>{holder.stringValue}</dummy>.copy(label = lhhaName)

      val items =
        (enclosingHolder child Names.Item nonEmpty) list
          <items>{
            for (item ← enclosingHolder child Names.Item)
            yield
              <item>{
                for (el ← item child *)
                yield
                  <dummy>{el.stringValue}</dummy>.copy(label = el.localname)
              }</item>

          }</items>


      // TODO: multiple alerts: level of alert

      lhhas ++ items
    }

    val selectedControls =
      (controls.values map collectByErasedType[XFormsSingleNodeControl] flatten) filter isBoundToFormDataInScope

    val repeatDepth = doc.getStaticState.topLevelPart.repeatDepthAcrossParts

    def sortString(control: XFormsControl) =
      ErrorSummary.controlSortString(control.absoluteId, repeatDepth)

    val sortedControls =
      selectedControls.to[List].sortWith(sortString(_) < sortString(_))

    val controlMetadata =
      for {
        control           ← sortedControls
        staticControl     ← collectByErasedType[StaticLHHASupport](control.staticControl)
        resourcesInstance ← resourcesInstance(control)
        if ! staticControl.staticId.startsWith("fb-lhh-editor-for-") // HACK for this in grid.xbl
        controlName       ← FormRunner.controlNameFromIdOpt(control.getId)
        boundNode         ← control.boundNode
        dataHash          = SubmissionUtils.dataNodeHash(boundNode)
      } yield
        dataHash →
          <control
            name={controlName}
            type={staticControl.localName}
            datatype={control.getTypeLocalNameOpt.orNull}>{
            for ((lang, resourcesRoot) ← iterateResources(resourcesInstance))
            yield
              <resources lang={lang}>{resourcesForControl(staticControl, lang, resourcesRoot, controlName)}</resources>
          }{
            for (valueControl ← collectByErasedType[XFormsValueControl](control).toList)
            yield
              <value>{valueControl.getValue}</value>
          }</control>

    import scala.{xml ⇒ sxml}

    def addAttribute(elem: Elem, name: String, value: String) =
      elem % sxml.Attribute(None, name, sxml.Text(value), sxml.Null)

    val groupedMetadata =
      controlMetadata groupByKeepOrder (x ⇒ x._2) map
      { case (elem, hashes) ⇒ addAttribute(elem, "for", hashes map (_._1) mkString " ")}

    <metadata>{groupedMetadata}</metadata>
  }

  def createFormMetadataDocument2(doc: XFormsContainingDocument): List[ControlDetails] = {

    val controls = doc.getControls.getCurrentControlTree.effectiveIdsToControls

    def instanceInScope(control: XFormsSingleNodeControl, staticId: String): Option[XFormsInstance] =
      control.container.resolveObjectByIdInScope(control.getEffectiveId, staticId, None) flatMap
        collectByErasedType[XFormsInstance]

    def resourcesInstance(control: XFormsSingleNodeControl): Option[XFormsInstance] =
      instanceInScope(control, FormResources)

    def isBoundToFormDataInScope(control: XFormsSingleNodeControl): Boolean = {

      val boundNode = control.boundNode
      val data      = instanceInScope(control, FormInstance)

      (boundNode map (_.getDocumentRoot)) == (data map (_.root))
    }

    def iterateResources(resourcesInstance: XFormsInstance): Iterator[(Lang, NodeInfo)] =
      for (resource ← resourcesInstance.rootElement / Resource iterator)
      yield
        Lang(resource.attValue("*:lang")) → resource

    def resourcesForControl(
      staticControl : StaticLHHASupport,
      lang          : Lang,
      resourcesRoot : NodeInfo,
      controlName   : String
    ): (List[(LHHA, String)], List[Item]) = {

      val enclosingHolder = resourcesRoot descendant controlName take 1

      val lhhas =
        for {
          lhha   ← LHHA.values.to[List]
          if staticControl.hasLHHA(lhha)
          holder ← enclosingHolder child lhha.entryName
        } yield
          lhha → holder.stringValue

      val items =
        for ((item, position) ← enclosingHolder child Names.Item zipWithIndex)
        yield
          itemset.Item(
            label      = LHHAValue(item elemValue LHHA.Label.entryName, isHTML = false), // TODO isHTML
            help       = item elemValueOpt LHHA.Help.entryName flatMap (_.trimAllToOpt) map (LHHAValue(_, isHTML = false)), // TODO isHTML
            hint       = item elemValueOpt LHHA.Hint.entryName flatMap (_.trimAllToOpt) map (LHHAValue(_, isHTML = false)), // TODO isHTML
            value      = item elemValue Names.Value,
            attributes = Nil
          )(position)

      // TODO: multiple alerts: level of alert

      (lhhas, items.to[List])
    }

    val selectedControls =
      (controls.values map collectByErasedType[XFormsSingleNodeControl] flatten) filter (_.isRelevant) filter isBoundToFormDataInScope

    val repeatDepth = doc.getStaticState.topLevelPart.repeatDepthAcrossParts

    def sortString(control: XFormsControl) =
      ErrorSummary.controlSortString(control.absoluteId, repeatDepth)

    val sortedControls =
      selectedControls.to[List].sortWith(sortString(_) < sortString(_))

    val controlMetadata =
      for {
        control           ← sortedControls
        staticControl     ← collectByErasedType[StaticLHHASupport](control.staticControl)
        resourcesInstance ← resourcesInstance(control)
        if ! staticControl.staticId.startsWith("fb-lhh-editor-for-") // HACK for this in grid.xbl
        controlName       ← FormRunner.controlNameFromIdOpt(control.getId)
        boundNode         ← control.boundNode
        dataHash          = SubmissionUtils.dataNodeHash(boundNode)
      } yield
        dataHash → {

          val lhhaAndItemsIt =
            for ((lang, resourcesRoot) ← iterateResources(resourcesInstance))
              yield
                lang → resourcesForControl(staticControl, lang, resourcesRoot, controlName)

          val lhhaAndItemsList = lhhaAndItemsIt.to[List]

          val valueOpt =
            control collect {
              case c: XFormsSelectControl  ⇒

                val selectedLabels = c.findSelectedItems map (_.label.label) // TODO: HTML

                MultipleControlValue(c.getValue, selectedLabels) // TODO

              case c: XFormsSelect1Control ⇒

                val selectedLabel = c.findSelectedItem map (_.label.label) // TODO: HTML

                SingleControlValue(c.getValue, selectedLabel) // TODO

              case c: XFormsValueComponentControl if c.staticControl.bindingOrThrow.abstractBinding.modeSelection ⇒

                val selectionControlOpt = XXFormsItemset.findSelectionControl(c)

                selectionControlOpt match {
                  case Some(c: XFormsSelectControl) ⇒
                    val selectedLabels = c.findSelectedItems map (_.label.label)  // TODO: HTML
                    MultipleControlValue(c.getValue, selectedLabels) // TODO
                  case Some(c) ⇒
                    val selectedLabel = c.findSelectedItem map (_.label.label) // TODO: HTML
                    SingleControlValue(c.getValue, selectedLabel) // TODO
                  case None ⇒
                    throw new IllegalStateException
                }

              case c: XFormsOutputControl ⇒

                // Special case: if there is a "Calculated Value" control, but which has a blank value in the
                // instance and no initial/calculated expressions, then consider that this control doesn't have
                // a formatted value.
                val noCalculationAndIsEmpty =
                  ! c.bind.get.staticBind.hasDefaultOrCalculateBind && c.getValue.trimAllToOpt.isEmpty

                val formattedValue =
                  if (noCalculationAndIsEmpty)
                    None
                  else
                    c.getFormattedValue orElse Option(c.getValue)

                SingleControlValue(c.getValue, formattedValue)

              case c: XFormsValueControl ⇒

                SingleControlValue(c.getValue, c.getFormattedValue orElse Option(c.getValue))
            }

          ControlDetails(
            name         = controlName,
            typ          = staticControl.localName,
            level        = ErrorSummary.ancestorSectionsIt(control).size,
            datatype     = control.getTypeLocalNameOpt,
            lhhaAndItems = lhhaAndItemsList,
            value        = valueOpt,
            forHashes    = Nil
          )
        }

    controlMetadata groupByKeepOrder (x ⇒ x._2) map { case (elem, hashes) ⇒
      elem.copy(forHashes = hashes map (_._1))
    }
  }
}