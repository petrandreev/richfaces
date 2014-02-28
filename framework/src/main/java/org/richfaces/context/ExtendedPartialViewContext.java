/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.richfaces.context;

import static org.richfaces.ui.common.AjaxConstants.AJAX_COMPONENT_ID_PARAMETER;
import static org.richfaces.ui.common.AjaxConstants.ALL;
import static org.richfaces.ui.common.AjaxConstants.BEHAVIOR_EVENT_PARAMETER;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import javax.faces.FactoryFinder;
import javax.faces.component.UIComponent;
import javax.faces.component.UIViewRoot;
import javax.faces.component.visit.VisitCallback;
import javax.faces.component.visit.VisitContext;
import javax.faces.component.visit.VisitContextFactory;
import javax.faces.component.visit.VisitHint;
import javax.faces.context.FacesContext;
import javax.faces.context.PartialResponseWriter;
import javax.faces.context.PartialViewContext;
import javax.faces.context.PartialViewContextWrapper;
import javax.faces.event.PhaseId;

import org.richfaces.javascript.JavaScriptService;
import org.richfaces.javascript.ScriptUtils;
import org.richfaces.javascript.ScriptsHolder;
import org.richfaces.services.ServiceTracker;
import org.richfaces.ui.common.HtmlConstants;
import org.richfaces.util.FastJoiner;

import com.google.common.collect.Maps;

/**
 * <p>
 * The RichFaces custom version of PartialViewContext
 * </p>
 * <p>
 * Important differences of RichFaces implementation:
 * </p>
 * <ul>
 * <li>Values are resolved in runtime by visiting the request activator component and evaluating attributes</li>
 * <li>Usage of extended visit contexts in order to support meta-components processing</li>
 * <li>Support for auto-updateable Ajax components</li>
 * <li>Support for Ajax extensions like passing data to the client</li>
 * </ul>
 *
 * @author Nick Belaevski
 */
public class ExtendedPartialViewContext extends PartialViewContextWrapper {

    private static final String EXTENSION_ID = "org.richfaces.extension";
    private static final String BEFOREDOMUPDATE_ELEMENT_NAME = "beforedomupdate";
    private static final String COMPLETE_ELEMENT_NAME = "complete";
    private static final String RENDER_ELEMENT_NAME = "render";
    private static final String DATA_ELEMENT_NAME = "data";
    private static final String COMPONENT_DATA_ELEMENT_NAME = "componentData";
    private static final FastJoiner SPACE_JOINER = FastJoiner.on(' ');

    private static final String ATTRIBUTE_NAME = ExtendedPartialViewContext.class.getName();

    private FacesContext facesContext;
    private PartialViewContext wrappedViewContext;
    private PartialResponseWriter partialResponseWriter;
    private boolean released = false;

    // request data
    private ContextMode contextMode = null;
    private String activatorComponentId = null;
    private String behaviorEvent = null;

    // computed properties
    private Collection<String> executeIds = null;
    private Collection<String> renderIds = null;
    private Boolean renderAll = null;

    // activator component data
    private Collection<String> componentRenderIds = null;
    private String onbeforedomupdate;
    private String oncomplete;
    private Object responseData;
    private boolean limitRender = false;

    private Map<String, Object> responseComponentDataMap = Maps.newHashMap();
    private StringBuilder beforedomupdateHandler = new StringBuilder();
    private StringBuilder completeHandler = new StringBuilder();

    // current visit mode setup during #processPartial method
    private Stack<ExtendedVisitContextMode> visitMode = new Stack<ExtendedVisitContextMode>();


    public ExtendedPartialViewContext(PartialViewContext wrappedViewContext, FacesContext facesContext) {
        this.wrappedViewContext = wrappedViewContext;
        this.facesContext = facesContext;
        setInstance(facesContext, this);
    }

    /**
     * The partial view processing mode
     */
    private enum ContextMode {
        /**
         * Use wrapped {@link PartialViewContext} implementation for partial view processing.
         */
        WRAPPED,

        /**
         * Use RichFaces-specific partial view processing processing via {@link ExtendedPartialViewContext}
         */
        EXTENDED
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.faces.context.PartialViewContextWrapper#getWrapped()
     */
    @Override
    public PartialViewContext getWrapped() {
        return wrappedViewContext;
    }

    /**
     * Returns {@link FacesContext} for current partial view processing context
     */
    protected FacesContext getFacesContext() {
        return facesContext;
    }

    /**
     * Return current {@link ExtendedPartialViewContext} instance for given {@link FacesContext}
     */
    public static ExtendedPartialViewContext getInstance(FacesContext facesContext) {
        return (ExtendedPartialViewContext) facesContext.getAttributes().get(ATTRIBUTE_NAME);
    }

    /**
     * Setups given {@link ExtendedPartialViewContext} instance to context
     * @param facesContext
     * @param instance
     */
    private static void setInstance(FacesContext facesContext, ExtendedPartialViewContext instance) {
        facesContext.getAttributes().put(ATTRIBUTE_NAME, instance);
    }

    /**
     * <p>
     * This method detects in which phase we are and setups {@link ExtendedVisitContextMode} attribute.
     * </p>
     *
     * <p>
     * Then it delegates to wrapped implementation of {@link PartialViewContext#processPartial(PhaseId)}.
     * </p>
     *
     * <p>
     * The {@link ExtendedVisitContextMode} attribute is used by {@link ExtendedVisitContextFactory} to create either
     * {@link ExtendedExecuteVisitContext} or {@link ExtendedRenderVisitContext}.
     * </p>
     */
    @Override
    public void processPartial(PhaseId phaseId) {
        initializeContext();
        try {
            if (isProcessedExecutePhase(phaseId)) {
                setVisitMode(ExtendedVisitContextMode.EXECUTE);
            } else {
                setVisitMode(ExtendedVisitContextMode.RENDER);
            }
            wrappedViewContext.processPartial(phaseId);
        } finally {
            resetVisitMode();
        }
    }

    private boolean isProcessedExecutePhase(PhaseId phaseId) {
        return phaseId == PhaseId.APPLY_REQUEST_VALUES || phaseId == PhaseId.PROCESS_VALIDATIONS
                || phaseId == PhaseId.UPDATE_MODEL_VALUES;
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.faces.context.PartialViewContextWrapper#getExecuteIds()
     */
    @Override
    public Collection<String> getExecuteIds() {
        assertNotReleased();
        if (detectContextMode() == ContextMode.EXTENDED) {
            if (executeIds == null) {
                executeIds = new LinkedHashSet<String>();
                visitActivatorAtExecute();
            }
            return executeIds;
        } else {
            return wrappedViewContext.getExecuteIds();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.faces.context.PartialViewContextWrapper#getRenderIds()
     */
    @Override
    public Collection<String> getRenderIds() {
        assertNotReleased();
        if (detectContextMode() == ContextMode.EXTENDED) {
            if (renderIds == null) {
                renderIds = new LinkedHashSet<String>();
                visitActivatorAtRender();
            }
            return renderIds;
        } else {
            return wrappedViewContext.getRenderIds();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.faces.context.PartialViewContextWrapper#isExecuteAll()
     */
    @Override
    public boolean isExecuteAll() {
        assertNotReleased();
        if (detectContextMode() == ContextMode.EXTENDED) {
            return getExecuteIds().contains(ALL);
        } else {
            return wrappedViewContext.isExecuteAll();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.faces.context.PartialViewContextWrapper#isRenderAll()
     */
    @Override
    public boolean isRenderAll() {
        assertNotReleased();
        if (detectContextMode() == ContextMode.EXTENDED) {
            if (renderAll != null) {
                return renderAll.booleanValue();
            }

            return getRenderIds().contains(ALL);
        } else {
            return wrappedViewContext.isRenderAll();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see javax.faces.context.PartialViewContextWrapper#setRenderAll(boolean)
     */
    @Override
    public void setRenderAll(boolean renderAll) {
        assertNotReleased();
        this.renderAll = renderAll;
        super.setRenderAll(renderAll);
    }

    /**
     * Returns user-provided data as an extension for partial-response response element
     */
    public Object getResponseData() {
        return responseData;
    }

    /**
     * Returns data provided by components as an extension for partial-response response element
     */
    public Map<String, Object> getResponseComponentDataMap() {
        return responseComponentDataMap;
    }

    /**
     * Sets user-provided data as an extension for partial-response
     */
    public void setResponseData(Object responseData) {
        this.responseData = responseData;
    }

    /**
     * Append a script to oncomplete handler
     */
    public void appendOncomplete(Object handler) {
        if (handler != null) {
            completeHandler.append(handler.toString());
            completeHandler.append(';');
        }
    }

    /**
     * Prepend an oncomplete handler with given script
     */
    public void prependOncomplete(Object handler) {
        if (handler != null) {
            completeHandler.insert(0, ';');
            completeHandler.insert(0, handler.toString());
        }
    }

    /**
     * Return oncomplete handler as an extension for partial-response
     */
    public Object getOncomplete() {
        return completeHandler.toString();
    }

    /**
     * Append a script to onbeforedomupdate handler
     */
    public void appendOnbeforedomupdate(Object handler) {
        if (handler != null) {
            beforedomupdateHandler.append(handler.toString());
            beforedomupdateHandler.append(';');
        }
    }

    /**
     * Prepend an onbeforedomupdate handler with given script
     */
    public void prependOnbeforedomupdate(Object handler) {
        if (handler != null) {
            beforedomupdateHandler.insert(0, handler.toString());
            beforedomupdateHandler.insert(0, ';');
        }
    }

    /**
     * Return onbeforedomupdate handler as an extension for partial-response
     */
    public Object getOnbeforedomupdate() {
        return beforedomupdateHandler.toString();
    }

    /**
     * Returns true if rendering in current context is limited to components listed in activator's component <em>render</em> attribute.
     */
    public boolean isLimitRender() {
        return limitRender;
    }

    /**
     * Returns in which visit mode we currently operate in
     */
    public ExtendedVisitContextMode getVisitMode() {
        if (visitMode.isEmpty()) {
            return null;
        }
        return visitMode.peek();
    }

    /**
     * Set ups current visit mode to given value.
     *
     * Works as a stack because {@link #processPartial(javax.faces.event.PhaseId)} methods that sets this flag may nest.
     *
     * @see #resetVisitMode()
     */
    private void setVisitMode(ExtendedVisitContextMode visitMode) {
        this.visitMode.add(visitMode);
    }

    /**
     * Resets current visit mode.
     *
     * Works as a stack because {@link #processPartial(javax.faces.event.PhaseId)} methods that sets this flag may nest.
     * Partial processing needs to {@link #resetVisitMode()} before returning.
     */
    private void resetVisitMode() {
        this.visitMode.pop();
    }

    /**
     * We are wrapping {@link PartialResponseWriter} obtained from wrapped implementation into {@link ExtensionWritingPartialResponseWriter}.
     *
     * The wrapper makes sure the RichFaces-specific extensions are written into partial-response before the document is ended.
     */
    @Override
    public PartialResponseWriter getPartialResponseWriter() {
        assertNotReleased();
        if (partialResponseWriter == null) {
            partialResponseWriter = new ExtensionWritingPartialResponseWriter(wrappedViewContext.getPartialResponseWriter());
        }
        return partialResponseWriter;
    }

    /**
     * Makes sure the RichFaces-specific extensions are written into partial-response before the document is ended.
     */
    private class ExtensionWritingPartialResponseWriter extends PartialResponseWriterWrapper {

        public ExtensionWritingPartialResponseWriter(PartialResponseWriter wrapped) {
            super(wrapped);
        }

        /**
         * Render RichFaces-specific extensions and then {@link #endDocument()} finally.
         */
        @Override
        public void endDocument() throws IOException {
            try {
                FacesContext facesContext = FacesContext.getCurrentInstance();
                UIViewRoot viewRoot = facesContext.getViewRoot();


                addJavaScriptServicePageScripts(facesContext);
                renderExtensions(facesContext, viewRoot);
            } finally {
                super.endDocument();
            }
        }
    }

    /**
     * Visits activator component to collect attributes needed for execute phase
     */
    private void visitActivatorAtExecute() {
        if (detectContextMode() == ContextMode.EXTENDED) {

            ActivatorComponentExecuteCallback callback = new ActivatorComponentExecuteCallback(getFacesContext(), behaviorEvent);

            if (visitActivatorComponent(activatorComponentId, callback, EnumSet.noneOf(VisitHint.class))) {
                executeIds.addAll(callback.getExecuteIds());

                setupRenderCallbackData(callback);

                if (!executeIds.contains(ALL)) {
                    addImplicitExecuteIds(executeIds);
                }
            }
        }
    }

    /**
     * Copies the data collected from activator component processing to to this context
     */
    private void setupRenderCallbackData(ActivatorComponentRenderCallback callback) {
        componentRenderIds = callback.getRenderIds();
        onbeforedomupdate = callback.getOnbeforedomupdate();
        oncomplete = callback.getOncomplete();
        responseData = callback.getData();
        limitRender = callback.isLimitRender();
    }

    /**
     * Visits activator component to collect attributes needed for render phase
     */
    private void visitActivatorAtRender() {
        if (detectContextMode() == ContextMode.EXTENDED && !isRenderAll()) {
            ActivatorComponentRenderCallback callback = new ActivatorComponentRenderCallback(getFacesContext(), behaviorEvent);

            if (visitActivatorComponent(activatorComponentId, callback, EnumSet.noneOf(VisitHint.class))) {
                setupRenderCallbackData(callback);
            } else {
                // TODO - the same as for "execute"
            }

            // take collection value stored during execute
            if (componentRenderIds != null) {
                renderIds.addAll(componentRenderIds);
            }

            if (!Boolean.TRUE.equals(renderAll) && !renderIds.contains(ALL)) {
                addImplicitRenderIds(renderIds);

                appendOnbeforedomupdate(onbeforedomupdate);
                appendOncomplete(oncomplete);
                setResponseData(responseData);
            }
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.richfaces.context.ExtendedPartialViewContext#release()
     */
    @Override
    public void release() {
        assertNotReleased();

        super.release();

        setInstance(facesContext, null);
        facesContext = null;

        released = true;

        wrappedViewContext.release();
        wrappedViewContext = null;

        renderAll = null;
        executeIds = null;
        renderIds = null;

        limitRender = false;

        activatorComponentId = null;
        behaviorEvent = null;
        contextMode = null;
    }

    /**
     * Adding implicitly executed areas to the list of component that should be executed.
     *
     * This implementation handles just {@link UIViewRoot#METADATA_FACET_NAME} execution.
     */
    protected void addImplicitExecuteIds(Collection<String> executeIds) {
        if (!executeIds.isEmpty()) {
            UIViewRoot root = getFacesContext().getViewRoot();
            if (root.getFacetCount() > 0) {
                if (root.getFacet(UIViewRoot.METADATA_FACET_NAME) != null) {
                    executeIds.add(UIViewRoot.METADATA_FACET_NAME);
                }
            }
        }
    }

    /**
     * Adding implicitly renderer areas to the list of component that should be rendered.
     *
     *
     */
    protected void addImplicitRenderIds(Collection<String> renderIds) {
        if (!limitRender) {
            final FacesContext facesContext = getFacesContext();
            Collection<String> ajaxOutputs = AjaxOutputTracker.getAjaxOutputs(facesContext, facesContext.getViewRoot());
            renderIds.addAll(ajaxOutputs);
        }
    }

    /**
     * Add scripts collected by {@link JavaScriptService} as a partial response extension.
     */
    protected void addJavaScriptServicePageScripts(FacesContext context) {
        ScriptsHolder scriptsHolder = ServiceTracker.getService(JavaScriptService.class).getScriptsHolder(context);
        StringBuilder scripts = new StringBuilder();
        for (Object script : scriptsHolder.getScripts()) {
            scripts.append(ScriptUtils.toScript(script));
            scripts.append(";");
        }
        for (Object script : scriptsHolder.getPageReadyScripts()) {
            scripts.append(ScriptUtils.toScript(script));
            scripts.append(";");
        }
        if (scripts.length() > 0) {
            scripts.append("RichFaces.javascriptServiceComplete();");
            prependOncomplete(scripts.toString());
        }
    }

    /**
     * Render RichFaces specific extensions for partial-update such as:
     *
     * <ul>
     * <li>beforedumpdate</li>
     * <li>complete</li>
     * <li>render</li>
     * <li>data</li>
     * <li>componentData</li>
     * </ul>
     */
    protected void renderExtensions(FacesContext context, UIComponent component) throws IOException {
        Map<String, String> attributes = Collections.singletonMap(HtmlConstants.ID_ATTRIBUTE, context.getExternalContext()
                .encodeNamespace(EXTENSION_ID));
        PartialResponseWriter writer = context.getPartialViewContext().getPartialResponseWriter();
        boolean[] writingState = new boolean[] { false };

        Object onbeforedomupdate = this.getOnbeforedomupdate();
        if (onbeforedomupdate != null) {
            String string = onbeforedomupdate.toString();
            if (string.length() != 0) {
                startExtensionElementIfNecessary(writer, attributes, writingState);
                writer.startElement(BEFOREDOMUPDATE_ELEMENT_NAME, component);
                writer.writeText(onbeforedomupdate, null);
                writer.endElement(BEFOREDOMUPDATE_ELEMENT_NAME);
            }
        }

        Object oncomplete = this.getOncomplete();
        if (oncomplete != null) {
            String string = oncomplete.toString();
            if (string.length() != 0) {
                startExtensionElementIfNecessary(writer, attributes, writingState);
                writer.startElement(COMPLETE_ELEMENT_NAME, component);
                writer.writeText(oncomplete, null);
                writer.endElement(COMPLETE_ELEMENT_NAME);
            }
        }

        if (!this.getRenderIds().isEmpty()) {
            String renderIds = SPACE_JOINER.join(this.getRenderIds());
            startExtensionElementIfNecessary(writer, attributes, writingState);
            writer.startElement(RENDER_ELEMENT_NAME, component);
            writer.writeText(renderIds, null);
            writer.endElement(RENDER_ELEMENT_NAME);
        }

        Object responseData = this.getResponseData();
        if (responseData != null) {
            startExtensionElementIfNecessary(writer, attributes, writingState);
            writer.startElement(DATA_ELEMENT_NAME, component);

            AjaxDataSerializer serializer = ServiceTracker.getService(context, AjaxDataSerializer.class);
            writer.writeText(serializer.asString(responseData), null);

            writer.endElement(DATA_ELEMENT_NAME);
        }

        Map<String, Object> responseComponentDataMap = this.getResponseComponentDataMap();
        if (responseComponentDataMap != null && !responseComponentDataMap.isEmpty()) {
            startExtensionElementIfNecessary(writer, attributes, writingState);
            writer.startElement(COMPONENT_DATA_ELEMENT_NAME, component);

            AjaxDataSerializer serializer = ServiceTracker.getService(context, AjaxDataSerializer.class);
            writer.writeText(serializer.asString(responseComponentDataMap), null);

            writer.endElement(COMPONENT_DATA_ELEMENT_NAME);
        }

        endExtensionElementIfNecessary(writer, writingState);
    }

    /**
     * Asserts that this context was not released yet
     */
    private void assertNotReleased() {
        if (released) {
            throw new IllegalStateException("PartialViewContext already released!");
        }
    }

    /**
     * Visits activator component in order to collect its data
     */
    private boolean visitActivatorComponent(String componentActivatorId, VisitCallback visitCallback, Set<VisitHint> visitHints) {
        final FacesContext facesContext = getFacesContext();
        try {
            Set<String> idsToVisit = Collections.singleton(componentActivatorId);
            setVisitMode(ExtendedVisitContextMode.EXECUTE);
            VisitContextFactory visitContextFactory = (VisitContextFactory) FactoryFinder
                    .getFactory(javax.faces.FactoryFinder.VISIT_CONTEXT_FACTORY);
            VisitContext visitContext = visitContextFactory.getVisitContext(facesContext, idsToVisit, visitHints);
            return facesContext.getViewRoot().visitTree(visitContext, visitCallback);
        } finally {
            resetVisitMode();
        }
    }

    /**
     * Detects in what context mode should be this partial view request processed
     */
    protected ContextMode detectContextMode() {
        initializeContext();
        return contextMode;
    }

    /**
     * <p>Initializes context:</p>
     *
     * <ul>
     * <li>{@link #contextMode}</li>
     * <li>{@link #activatorComponentId}</li>
     * <li>{@link #behaviorEvent}</li>
     * </ul>
     */
    protected void initializeContext() {
        if (contextMode == null) {
            Map<String, String> requestParameterMap = getFacesContext().getExternalContext().getRequestParameterMap();
            activatorComponentId = requestParameterMap.get(AJAX_COMPONENT_ID_PARAMETER);

            if (activatorComponentId != null) {
                contextMode = ContextMode.EXTENDED;
                behaviorEvent = requestParameterMap.get(BEHAVIOR_EVENT_PARAMETER);
            } else {
                contextMode = ContextMode.WRAPPED;
            }
        }
    }

    private static void startExtensionElementIfNecessary(PartialResponseWriter partialResponseWriter,
            Map<String, String> attributes, boolean[] writingState) throws IOException {

        if (!writingState[0]) {
            writingState[0] = true;

            partialResponseWriter.startExtension(attributes);
        }
    }

    private static void endExtensionElementIfNecessary(PartialResponseWriter partialResponseWriter, boolean[] writingState)
            throws IOException {

        if (writingState[0]) {
            writingState[0] = false;

            partialResponseWriter.endExtension();
        }
    }
}
