/**
 * Copyright (C) 2010 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.control

import org.orbeon.dom.Element
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.xforms.BindingContext
import org.orbeon.oxf.xforms.analysis.ControlAnalysisFactory.ValueControl
import org.orbeon.oxf.xforms.analysis.ElementAnalysis
import org.orbeon.oxf.xforms.analysis.controls.ComponentControl
import org.orbeon.oxf.xforms.analysis.model.Instance
import org.orbeon.oxf.xforms.control.controls.InstanceMirror._
import org.orbeon.oxf.xforms.control.controls.{InstanceMirror, XXFormsComponentRootControl, XXFormsDynamicControl}
import org.orbeon.oxf.xforms.event.Dispatch.EventListener
import org.orbeon.oxf.xforms.event.events.{XFormsModelConstructDoneEvent, XFormsModelConstructEvent, XFormsReadyEvent}
import org.orbeon.oxf.xforms.event.{Dispatch, XFormsEvent, XFormsEvents}
import org.orbeon.oxf.xforms.model.{AllDefaultsStrategy, XFormsInstance}
import org.orbeon.oxf.xforms.state.ControlState
import org.orbeon.oxf.xforms.xbl.XBLContainer
import org.orbeon.oxf.xml.{SaxonUtils, XMLReceiverHelper}
import org.orbeon.saxon.om.VirtualNode
import org.orbeon.scaxon.NodeConversions.unsafeUnwrapElement
import org.w3c.dom.Node.ELEMENT_NODE
import org.xml.sax.helpers.AttributesImpl

import scala.collection.JavaConverters._

// A component control with native support for a value
class XFormsValueComponentControl(
  container   : XBLContainer,
  parent      : XFormsControl,
  element     : Element,
  effectiveId : String
) extends XFormsComponentControl(
  container,
  parent,
  element,
  effectiveId
) with XFormsValueControl {

  override type Control <: ComponentControl with ValueControl

  // Don't expose an external value unless explicitly allowed
  override def handleExternalValue = staticControl.abstractBinding.modeExternalValue

  // Try to use the value type and associated format
  // TODO: Later: declarative support for formatted values in XBL controls.
  // See https://github.com/orbeon/orbeon-forms/issues/2428
  override def getFormattedValue: Option[String] = valueWithDefaultFormat

  // TODO
  override def findAriaByControlEffectiveId = None

  override def outputAjaxDiffUseClientValue(
    previousValue   : Option[String],
    previousControl : Option[XFormsValueControl],
    content         : Option[XMLReceiverHelper ⇒ Unit])(implicit
    ch              : XMLReceiverHelper
  ): Unit = {
    // NOTE: Don't output any nested content. The value is handled separately, see:
    // https://github.com/orbeon/orbeon-forms/issues/3909
    super.outputAjaxDiff(
      previousControl,
      None
    )

    outputAriaByAtts(previousValue, previousControl, ch)
  }
}

// A component control with or without a value
class XFormsComponentControl(
  container   : XBLContainer,
  parent      : XFormsControl,
  element     : Element,
  effectiveId : String
) extends XFormsSingleNodeContainerControl(container, parent, element, effectiveId) {

  override type Control <: ComponentControl

  private var _nestedContainerOpt: Option[XBLContainer] = None
  def nestedContainer = _nestedContainerOpt.get

  // If the component handles LHHA itself, they are considered under of the nested container
  override def lhhaContainer = nestedContainer

  private var _listeners: Seq[(XFormsInstance, EventListener)] = Nil

  // Create nested container upon object instantiation
  // NOTE: 2018-02-23: We wanted to create this upon `onCreate` and destroy it upon `onDestroy`, but there are
  // too many assumptions that the container is available even if the control is non-relevant for this to work
  // easily. Might want to revisit later. However, if we have a lazy `ConcreteBinding`, the container will still
  // not be created immediately because the container needs the inner scope, which is known only when the
  // `ConcreteBinding` is available.
  // See also https://github.com/orbeon/orbeon-forms/issues/4018
  createNestedContainer()

  // React to external events only if we are relevant OR upon receiving xforms-disabled
  def canRunEventHandlers(event: XFormsEvent): Boolean =
    isRelevant || event.name == "xforms-disabled"

  private def createNestedContainer(): Option[XBLContainer] = {

    assert(_nestedContainerOpt.isEmpty)

    _nestedContainerOpt =
      staticControl.bindingOpt map { concreteBinding ⇒
        container.createChildContainer(this, concreteBinding) |!> (_.addAllModels())
      }

    _nestedContainerOpt
  }

  // Destroy container and models if any
  def destroyNestedContainer(): Unit =
    _nestedContainerOpt foreach { nestedContainer ⇒
      destroyMirrorListenerIfNeeded()
      nestedContainer.destroy()
      _nestedContainerOpt = None
    }

  // For xxf:dynamic updates of top-level XBL shadow trees
  def recreateNestedContainer(): Option[XBLContainer] =
    createNestedContainer() |!> { _ ⇒
      if (isRelevant)
        initializeModels()
    }

  private def modeBinding = staticControl.abstractBinding.modeBinding

  // Only handle binding if we support modeBinding
  override protected def computeBinding(parentContext: BindingContext): BindingContext =
    if (modeBinding)
      super.computeBinding(parentContext)
    else
      super.computeBindingCopy(parentContext)

  override def bindingContextForChildOpt: Option[BindingContext] =
    _nestedContainerOpt map { nestedContainer ⇒
      // Start with inner context
      // For nested event handlers, this still works, because the nested handler can never match the inner scope. So
      // the context goes inner context → component binding.
      val contextStack = nestedContainer.getContextStack
      contextStack.setParentBindingContext(bindingContext)
      contextStack.resetBindingContext
      contextStack.getCurrentBindingContext
    }

  override def onCreate(restoreState: Boolean, state: Option[ControlState], update: Boolean): Unit = {

    super.onCreate(restoreState, state, update)

    if (staticControl.hasLazyBinding && staticControl.bindingOpt.isEmpty) {

      // Only update the static tree. The dynamic tree is created later.
      XXFormsDynamicControl.createOrUpdateStaticShadowTree(this, None)
      assert(staticControl.bindingOpt.isDefined)

      // See https://github.com/orbeon/orbeon-forms/issues/4018
      //
      // The nested `XBLContainer` needs to be created if the lazy static binding got just created. The reason is that upon control
      // construction, if the static binding was missing, the nested `XBLContainer` was not created either.
      recreateNestedContainer()

      if (update)
        XXFormsDynamicControl.updateDynamicShadowTree(this, dispatchEvents = false)
    }

    if (Controls.isRestoringDynamicState)
      restoreModels()
    else
      initializeModels()

    addEnabledListener()
  }

  // Attach a mirror listener if needed
  // Return the reference node if a listener was created
  private def createMirrorListener(mirrorInstance: XFormsInstance, referenceNode: VirtualNode): Option[VirtualNode] =
    _nestedContainerOpt map { nestedContainer ⇒

      val outerDocument = referenceNode.getDocumentRoot
      val outerInstance = containingDocument.getInstanceForNode(outerDocument)

      val newListenerWithCycleDetector = new ListenerCycleDetector

      val outerListener = toEventListener(
        newListenerWithCycleDetector(
          containingDocument,
          toInnerInstanceNode(
            referenceNode,
            nestedContainer.partAnalysis,
            nestedContainer,
            findOuterInstanceDetailsXBL(mirrorInstance, referenceNode)
          )
        )
      )

      val innerListener = toEventListener(
        newListenerWithCycleDetector(
          containingDocument,
          toOuterInstanceNodeXBL(outerInstance, referenceNode, nestedContainer.partAnalysis)
        )
      )

      // Set outer and inner listeners

      _listeners = List(outerInstance → outerListener, mirrorInstance → innerListener)

      _listeners foreach { case (instance, listener) ⇒
        InstanceMirror.addListener(instance, listener)
      }

      referenceNode
    }

  private def destroyMirrorListenerIfNeeded(): Unit = {
    _listeners foreach { case (instance, listener) ⇒
      InstanceMirror.removeListener(instance, listener)
    }

    _listeners = Nil
  }

  override def onDestroy(update: Boolean): Unit = {

    // NOTE: We do not *remove* the nested `XBLContainer` (see comments above). However, we should still destroy the container's
    // models. We should look into that!

    removeEnabledListener()
    destroyMirrorListenerIfNeeded()

    super.onDestroy(update)

    if (staticControl.hasLazyBinding && staticControl.bindingOpt.isDefined) {

      if (update) {
        containingDocument.getControls.getCurrentControlTree.deindexSubtree(this, includeCurrent = false)
        clearChildren()
        destroyNestedContainer()
      }

      staticControl.part.clearShadowTree(staticControl)
      containingDocument.addControlStructuralChange(prefixedId)
    }
  }

  private def initializeModels(): Unit =
    _nestedContainerOpt foreach { nestedContainer ⇒

      // `xforms-model-construct` without RRR
      for (model ← nestedContainer.models) {
        Dispatch.dispatchEvent(new XFormsModelConstructEvent(model, rrr = false))
        // NOTE: `xforms-model-construct` already does a `markStructuralChange()` but without `AllDefaultsStrategy`
        model.markStructuralChange(None, AllDefaultsStrategy)
      }

      initializeMirrorListenerIfNeeded(dispatch = false)

      // Do RRR as xforms-model-construct didn't do it
      for (model ← nestedContainer.models) {
        model.doRebuild()
        model.doRecalculateRevalidate()
      }

      // `xforms-model-construct-done`
      for (model ← nestedContainer.models)
        Dispatch.dispatchEvent(new XFormsModelConstructDoneEvent(model))
    }

  private def restoreModels(): Unit = {
    nestedContainer.restoreModelsState(deferRRR = true)
    initializeMirrorListenerIfNeeded(dispatch = false)
    // Do RRR as isRestoringDynamicState() didn't do it
    for (model ← nestedContainer.models) {
      model.doRebuild()
      model.doRecalculateRevalidate()
    }
  }

  private def initializeMirrorListenerIfNeeded(dispatch: Boolean): Option[XFormsInstance] = {

    // NOTE: Must be called after xforms-model-construct so that instances are present
    def findMirrorInstance = (
      nestedContainer.models.iterator
      flatMap (_.getInstances.asScala)
      find    (_.instance.element.attributeValue("mirror") == "true")
    )

    // Process mirror instance if any
    findMirrorInstance map { mirrorInstance ⇒

      // Reference node must be a wrapped element
      // Also support case where there is no binding, and in which case use the binding context. This is done
      // because Form Builder doesn't place a ref or bind on section template components as of 2013-01-17.
      val referenceNode = (if (modeBinding) boundItemOpt else contextForBinding) collect {
        case node: VirtualNode if node.getNodeKind == ELEMENT_NODE ⇒ node
      }

      // Create new doc rooted at reference node
      val doc =
        Instance.extractDocument(
          element               = unsafeUnwrapElement(referenceNode.get),
          excludeResultPrefixes = Set(),
          readonly              = false,
          exposeXPathTypes      = mirrorInstance.exposeXPathTypes,
          removeInstanceData    = true
        )

      // Update initial instance
      mirrorInstance.replace(doc, dispatch)

      // Create the listeners
      createMirrorListener(mirrorInstance, referenceNode.get)

      mirrorInstance
    }
  }

  private var _enabledListener: Option[Dispatch.EventListener] = None

  private def addEnabledListener(): Unit = {

    assert(_enabledListener.isEmpty)

    // Logic: when the component control receives the `xforms-enabled` event (which occurs during refresh after
    // the nested models have already been initialized) we dispatch `xforms-ready` to the nested models. This is
    // for consistency with the top-level `xforms-ready` which occurs when the control tree has been initialized.
    // We could have considered using another event, as the name `xforms-ready` does not fully reflect the
    // meaning associated with the top-level. On the other hand, this makes it easier to translate a top-level
    // model into a nested model.
    val newListener: Dispatch.EventListener =
      _ ⇒ {

        // Remove during first run
        removeEnabledListener()

        for {
          container ← _nestedContainerOpt.iterator
          model     ← container.models
        } locally {
          Dispatch.dispatchEvent(new XFormsReadyEvent(model))
        }
      }

    _enabledListener = Some(newListener)

    addListener(XFormsEvents.XFORMS_ENABLED, newListener)
  }

  private def removeEnabledListener(): Unit =
    _enabledListener foreach { _ ⇒
      removeListener(XFormsEvents.XFORMS_ENABLED, _enabledListener)
      _enabledListener = None
    }

  override def onBindingUpdate(oldBinding: BindingContext, newBinding: BindingContext): Unit = {
    super.onBindingUpdate(oldBinding, newBinding)
    val isNodesetChange = ! SaxonUtils.compareItemSeqs(oldBinding.nodeset.asScala, newBinding.nodeset.asScala)
    if (isNodesetChange) {
      destroyMirrorListenerIfNeeded()
      initializeMirrorListenerIfNeeded(dispatch = true) foreach { mirrorInstance ⇒
        // If the instance was updated, it is due for an RRR, but nobody will check that before the refresh is done, so do it here.
        mirrorInstance.model.doRebuild()
        mirrorInstance.model.doRecalculateRevalidate()
      }
    }
  }

  // This is called iif the iteration index changes
  override def updateEffectiveId(): Unit = {

    // Update rest of control tree
    super.updateEffectiveId()

    // Update container with new effective id
    nestedContainer.updateEffectiveId(getEffectiveId)
  }

  override def iterationRemoved(): Unit = {
    // Inform descendants
    super.iterationRemoved()

    // Destroy container and models if any
    destroyNestedContainer()
  }

  // Simply delegate but switch the container
  override def buildChildren(
    buildTree: (XBLContainer, BindingContext, ElementAnalysis, Seq[Int]) ⇒ Option[XFormsControl],
    idSuffix: Seq[Int]
  ): Unit =
    if (staticControl.bindingOpt.isDefined)
      Controls.buildChildren(
        control   = this,
        children  = staticControl.children,
        buildTree = (_, bindingContext, staticElement, idSuffix) ⇒ buildTree(nestedContainer, bindingContext, staticElement, idSuffix),
        idSuffix  = idSuffix
      )

  // Get the control at the root of the inner scope of the component
  def innerRootControl = children collectFirst { case root: XXFormsComponentRootControl ⇒ root } get

  private lazy val handleLHHA =
    staticControl.abstractBinding.modeLHHA && ! staticControl.abstractBinding.modeLHHACustom

  // Don't add Ajax LHHA for custom-lhha mode
  override def addAjaxLHHA(attributesImpl: AttributesImpl, previousControlOpt: Option[XFormsControl]) =
    handleLHHA && super.addAjaxLHHA(attributesImpl, previousControlOpt)

  // Consider LHHA hasn't externally changed for custom-lhha mode
  override def compareLHHA(other: XFormsControl) =
    ! handleLHHA || super.compareLHHA(other)
}