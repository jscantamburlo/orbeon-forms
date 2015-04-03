/**
 * Copyright (C) 2015 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.control.controls

import java.{util ⇒ ju}

import org.dom4j.Element
import org.orbeon.oxf.xforms.XFormsConstants
import org.orbeon.oxf.xforms.XFormsUtils.namespaceId
import org.orbeon.oxf.xforms.control.ControlLocalSupport.XFormsControlLocal
import org.orbeon.oxf.xforms.control.{Focus, XFormsControl, XFormsNoSingleNodeContainerControl}
import org.orbeon.oxf.xforms.event.XFormsEvents._
import org.orbeon.oxf.xforms.event.events.{XFormsFocusEvent, XXFormsDialogOpenEvent}
import org.orbeon.oxf.xforms.event.{Dispatch, XFormsEvent}
import org.orbeon.oxf.xforms.state.ControlState
import org.orbeon.oxf.xforms.xbl.XBLContainer
import org.orbeon.oxf.xml.XMLReceiverHelper
import org.orbeon.oxf.xml.XMLReceiverHelper.CDATA
import org.xml.sax.helpers.AttributesImpl

private case class XXFormsDialogControlLocal(
    var visible             : Boolean,
    var constrainToViewport : Boolean,
    var neighborControlId   : Option[String]
) extends XFormsControlLocal

// Represents an extension xxf:dialog control.
class XXFormsDialogControl(
    container   : XBLContainer, 
    parent      : XFormsControl, 
    element     : Element, 
    effectiveId : String
) extends XFormsNoSingleNodeContainerControl(container, parent, element, effectiveId) {

    // NOTE: Attributes logic duplicated in XXFormsDialogHandler.
    // TODO: Should be in the static state.

    // Commented out as those are not used currently.
    // private var level = Option(element.attributeValue("level")) getOrElse {
    //     // Default is "modeless" for "minimal" appearance, "modal" otherwise
    //     if (appearances(XFormsConstants.XFORMS_MINIMAL_APPEARANCE_QNAME)) "modeless" else "modal"
    // }
    //
    // private val close                    = ! ("false" == element.attributeValue("close"))
    // private val draggable                = ! ("false" == element.attributeValue("draggable"))

    private val defaultNeighborControlId = Option(element.attributeValue("neighbor"))
    private val initiallyVisible         = element.attributeValue("visible") == "true"

    // Initial local state
    setLocal(XXFormsDialogControlLocal(initiallyVisible, constrainToViewport = false, None))

    override def onCreate(restoreState: Boolean, state: Option[ControlState]): Unit = {

        super.onCreate(restoreState, state)
        
        state match {
            case Some(ControlState(_, _, keyValues)) ⇒
                setLocal(XXFormsDialogControlLocal(
                    visible             = keyValues("visible") == "true",
                    constrainToViewport = keyValues.get("constrain") exists (_ == "true"),
                    neighborControlId   = keyValues.get("neighbor")
                ))
            case None if restoreState ⇒
                // This can happen with xxf:dynamic, which does not guarantee the stability of ids, therefore state for a
                // particular control might not be found.
                setLocal(XXFormsDialogControlLocal(
                    visible             = initiallyVisible,
                    constrainToViewport = false,
                    neighborControlId   = None
                ))
            case _ ⇒
        }
    }

    override def getJavaScriptInitialization = getCommonJavaScriptInitialization

    private def currentLocal = getCurrentLocal.asInstanceOf[XXFormsDialogControlLocal]

    def isVisible             = currentLocal.visible
    def wasVisible            = getInitialLocal.asInstanceOf[XXFormsDialogControlLocal].visible
    def neighborControlId     = currentLocal.neighborControlId orElse defaultNeighborControlId
    def isConstrainToViewport = currentLocal.constrainToViewport

    override def performTargetAction(event: XFormsEvent): Unit = {
        super.performTargetAction(event)
        event.name match {
            case XXFORMS_DIALOG_OPEN ⇒
                val dialogOpenEvent = event.asInstanceOf[XXFormsDialogOpenEvent]

                val localForUpdate = getLocalForUpdate.asInstanceOf[XXFormsDialogControlLocal]
                localForUpdate.visible             = true
                localForUpdate.neighborControlId   = dialogOpenEvent.neighbor
                localForUpdate.constrainToViewport = dialogOpenEvent.constrainToViewport

                containingDocument.getControls.markDirtySinceLastRequest(true)
                containingDocument.getControls.doPartialRefresh(this)
            case XXFORMS_DIALOG_CLOSE ⇒
                val localForUpdate = getLocalForUpdate.asInstanceOf[XXFormsDialogControlLocal]
                localForUpdate.visible             = false
                localForUpdate.neighborControlId   = None
                localForUpdate.constrainToViewport = false

                containingDocument.getControls.markDirtySinceLastRequest(false)
                containingDocument.getControls.doPartialRefresh(this)
            case _ ⇒
        }
    }

    override def performDefaultAction(event: XFormsEvent): Unit = {
        event.name match {
            case XXFORMS_DIALOG_OPEN ⇒
                // If dialog is closed and the focus is within the dialog, remove the focus
                // NOTE: Ideally, we should get back to the control that had focus before the dialog opened if possible.
                if (isVisible && ! Focus.isFocusWithinContainer(this))
                    Dispatch.dispatchEvent(new XFormsFocusEvent(this, false))
            case XXFORMS_DIALOG_CLOSE ⇒
                // If dialog is open and the focus has not been set within the dialog, attempt to set the focus within
                if (! isVisible && Focus.isFocusWithinContainer(this))
                    Focus.removeFocus(containingDocument)
            case _ ⇒
        }
        super.performDefaultAction(event)
    }

    override def serializeLocal = {

        val local = currentLocal
        val result = new ju.HashMap[String, String](3)

        result.put("visible", local.visible.toString)
        if (local.visible) {
            result.put("constrain", local.constrainToViewport.toString)
            local.neighborControlId foreach (result.put("neighbor", _))
        }
        result
    }

    override def equalsExternal(other: XFormsControl): Boolean = {
        if (other == null)
            return false
        
        // NOTE: don't give up on "this == other" because there can be a difference just in XFormsControlLocal
        // NOTE: We only compare on isVisible as we don't support just changing other attributes for now
        
        val otherDialog = other.asInstanceOf[XXFormsDialogControl]
        if (otherDialog.wasVisible != isVisible)
            return false
        
        super.equalsExternal(other)
    }

    override def outputAjaxDiff(
        ch                    : XMLReceiverHelper,
        other                 : XFormsControl,
        attributesImpl        : AttributesImpl,
        isNewlyVisibleSubtree : Boolean
    ): Unit = {

        locally {
            val doOutputElement = addAjaxAttributes(attributesImpl, isNewlyVisibleSubtree, other)
            if (doOutputElement)
                ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "control", attributesImpl)
        }

        locally {
            // NOTE: This uses visible/hidden. But we could also handle this with relevant="true|false".
            // 2015-04-01: Unsure if note above still makes sense.
            val otherDialog = other.asInstanceOf[XXFormsDialogControl]
            var doOutputElement = false
            
            val atts = new AttributesImpl
            atts.addAttribute("", "id", "id", CDATA, namespaceId(containingDocument, getEffectiveId))
            
            val visible = isVisible
            if (isNewlyVisibleSubtree || visible != otherDialog.wasVisible) {
                atts.addAttribute("", "visibility", "visibility", CDATA, if (visible) "visible" else "hidden")
                doOutputElement = true
            }
            if (visible) {
                val neighborOpt = neighborControlId
                if (isNewlyVisibleSubtree || neighborOpt != otherDialog.neighborControlId) {
                    neighborOpt foreach { neighbor ⇒
                        atts.addAttribute("", "neighbor", "neighbor", CDATA, namespaceId(containingDocument, neighbor))
                        doOutputElement = true
                    }
                }
                val constrain = isConstrainToViewport
                if (isNewlyVisibleSubtree || constrain != otherDialog.isConstrainToViewport) {
                    atts.addAttribute("", "constrain", "constrain", CDATA, constrain.toString)
                    doOutputElement = true
                }
            }
            if (doOutputElement)
                ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "div", atts)
        }
    }

    override def contentVisible = isVisible
}