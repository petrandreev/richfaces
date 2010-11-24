/**
 * License Agreement.
 *
 * Rich Faces - Natural Ajax for Java Server Faces (JSF)
 *
 * Copyright (C) 2007 Exadel, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License version 2.1 as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
 */

package org.richfaces.renderkit.util;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.faces.component.UIComponent;
import javax.faces.context.FacesContext;
import javax.faces.context.PartialResponseWriter;

import org.ajax4jsf.component.AjaxComponent;
import org.ajax4jsf.context.AjaxContext;
import org.richfaces.application.ServiceTracker;
import org.richfaces.renderkit.AjaxDataSerializer;
import org.richfaces.renderkit.HtmlConstants;

import com.google.common.base.Strings;


/**
 * @author shura
 *         <p/>
 *         Some utilites for render AJAX components.
 */
public final class CoreAjaxRendererUtils {

    /**
     * Attribute for keep JavaScript function name for call before updating
     * DOM tree.
     */
    public static final String ONBEFOREDOMUPDATE_ATTR_NAME = "onbeforedomupdate";

    /**
     * Attribute for keep JavaScript function name for call after complete
     * request.
     */
    public static final String ONCOMPLETE_ATTR_NAME = "oncomplete";

    public static final String DATA_ATTR_NAME = "data";

    /**
     * Attribute to keep
     */
    public static final String LIMITRENDER_ATTR_NAME = "limitRender";

    /**
     * @since 3.3.0
     */
    public static final String AJAX_PROCESS_ATTRIBUTE = "process";
    public static final String AJAX_REGIONS_ATTRIBUTE = "reRender";

    private static final String EXTENSION_ID = "org.richfaces.extension";
    
    private static final String BEFOREDOMUPDATE_ELEMENT_NAME = "beforedomupdate";
    private static final String COMPLETE_ELEMENT_NAME = "complete";
    private static final String DATA_ELEMENT_NAME = "data";
    private static final String COMPONENT_DATA_ELEMENT_NAME = "componentData";
    private static final Pattern ID_SPLIT_PATTERN = Pattern.compile("\\s*(\\s|,)\\s*");

    private CoreAjaxRendererUtils() {
    }

    private static void startExtensionElementIfNecessary(
        PartialResponseWriter partialResponseWriter,
        Map<String, String> attributes,
        boolean[] writingState) throws IOException {

        if (!writingState[0]) {
            writingState[0] = true;

            partialResponseWriter.startExtension(attributes);
        }
    }

    private static void endExtensionElementIfNecessary(
        PartialResponseWriter partialResponseWriter,
        boolean[] writingState) throws IOException {

        if (writingState[0]) {
            writingState[0] = false;

            partialResponseWriter.endExtension();
        }
    }

    public static void renderAjaxExtensions(FacesContext facesContext, UIComponent component) throws IOException {
        AjaxContext ajaxContext = AjaxContext.getCurrentInstance(facesContext);

        Map<String, String> attributes = Collections.singletonMap(HtmlConstants.ID_ATTRIBUTE,
            facesContext.getExternalContext().encodeNamespace(EXTENSION_ID));
        PartialResponseWriter writer = facesContext.getPartialViewContext().getPartialResponseWriter();
        boolean[] writingState = new boolean[]{false};

        Object onbeforedomupdate = ajaxContext.getOnbeforedomupdate();
        if (onbeforedomupdate != null) {
            String string = onbeforedomupdate.toString();
            if (string.length() != 0) {
                startExtensionElementIfNecessary(writer, attributes, writingState);
                writer.startElement(BEFOREDOMUPDATE_ELEMENT_NAME, component);
                writer.writeText(onbeforedomupdate, null);
                writer.endElement(BEFOREDOMUPDATE_ELEMENT_NAME);
            }
        }

        Object oncomplete = ajaxContext.getOncomplete();
        if (oncomplete != null) {
            String string = oncomplete.toString();
            if (string.length() != 0) {
                startExtensionElementIfNecessary(writer, attributes, writingState);
                writer.startElement(COMPLETE_ELEMENT_NAME, component);
                writer.writeText(oncomplete, null);
                writer.endElement(COMPLETE_ELEMENT_NAME);
            }
        }

        Object responseData = ajaxContext.getResponseData();
        if (responseData != null) {
            startExtensionElementIfNecessary(writer, attributes, writingState);
            writer.startElement(DATA_ELEMENT_NAME, component);

            AjaxDataSerializer serializer = ServiceTracker.getService(facesContext, AjaxDataSerializer.class);
            writer.writeText(serializer.asString(responseData), null);

            writer.endElement(DATA_ELEMENT_NAME);
        }

        Map<String, Object> responseComponentDataMap = ajaxContext.getResponseComponentDataMap();
        if (responseComponentDataMap != null && !responseComponentDataMap.isEmpty()) {
            startExtensionElementIfNecessary(writer, attributes, writingState);
            writer.startElement(COMPONENT_DATA_ELEMENT_NAME, component);

            AjaxDataSerializer serializer = ServiceTracker.getService(facesContext, AjaxDataSerializer.class);
            writer.writeText(serializer.asString(responseComponentDataMap), null);

            writer.endElement(COMPONENT_DATA_ELEMENT_NAME);
        }
        
        endExtensionElementIfNecessary(writer, writingState);

    }

    /**
     * Split parameter string into array of strings.
     * @param valuesSet
     * @return
     */
    public static String[] asIdsArray(String valuesSet) {
        return IdSplitBuilder.split(valuesSet);
    }

    /**
     * Get list of clientId's for given component
     *
     * @param uiComponent
     * @return List of areas Id's , updated by this component.
     */
    public static Set<String> getAjaxAreas(UIComponent uiComponent) {
        Object areas;

        if (uiComponent instanceof AjaxComponent) {
            areas = ((AjaxComponent) uiComponent).getReRender();
        } else {
            areas = uiComponent.getAttributes().get(AJAX_REGIONS_ATTRIBUTE);
        }

        return asIdsSet(areas);
    }

    /**
     * Returns set of areas to be processed as a result of this component action invocation
     *
     * @param component
     * @return set of IDs that should be processed as a
     * @since 3.3.0
     */
    public static Set<String> getAjaxAreasToProcess(UIComponent component) {
        Object areas;

        if (component instanceof AjaxComponent) {
            areas = ((AjaxComponent) component).getProcess();
        } else {
            areas = component.getAttributes().get(AJAX_PROCESS_ATTRIBUTE);
        }

        return asIdsSet(areas);
    }
    
    public static Set<String> asSimpleSet(Object valueToSet) {
        return asSet(valueToSet, false);
    }

    public static Set<String> asIdsSet(Object valueToSet) {
        return asSet(valueToSet, true);
    }
    
    @SuppressWarnings("unchecked")
    private static Set<String> asSet(Object valueToSet, boolean idsSet) {
        if (null != valueToSet) {

            // Simplest case - set.
            if (valueToSet instanceof Set) {
                return new LinkedHashSet<String>((Set<String>) valueToSet);
            } else if (valueToSet instanceof Collection) { // Other collections.
                return new LinkedHashSet<String>((Collection<String>) valueToSet);
            } else if (Object[].class.isAssignableFrom(valueToSet.getClass())) { // Array
                return new LinkedHashSet<String>(Arrays.asList((String[]) valueToSet));
            } else if (valueToSet instanceof String) { // Tokenize string.
                String areasString = ((String) valueToSet).trim();

                if (areasString.contains(",") || areasString.contains(" ")) {
                    String[] values;
                    if (idsSet) {
                        values = IdSplitBuilder.split(areasString);
                    } else {
                        values = ID_SPLIT_PATTERN.split(areasString);
                    }
                    
                    Set<String> result = new LinkedHashSet<String>(values.length);
                    for (String value : values) {
                        if (Strings.isNullOrEmpty(value)) {
                            continue;
                        }

                        result.add(value);
                    }

                    return result;
                } else {
                    Set<String> areasSet = new LinkedHashSet<String>(5);

                    if (!Strings.isNullOrEmpty(areasString)) {
                        areasSet.add(areasString);
                    }

                    return areasSet;
                }
            }
        }

        return null;
    }


    /**
     * Calculate, must be component render only given areas, or all sended from
     * server.
     *
     * @param component
     * @return <code>true</code> if client must render ONLY given areas.
     */
    public static boolean isAjaxLimitRender(UIComponent component) {
        boolean result = false;

        if (component instanceof AjaxComponent) {
            result = ((AjaxComponent) component).isLimitRender();
        } else {
            try {
                result = ((Boolean) component.getAttributes().get(LIMITRENDER_ATTR_NAME)).booleanValue();
            } catch (NullPointerException e) {

                // NullPointer - ignore ...
            } catch (ClassCastException e1) {

                // not Boolean - false ...
            }
        }

        return result;
    }


    /**
     * Get function name for call on completed ajax request.
     *
     * @param component for wich calculate function name
     * @return name of JavaScript function or <code>null</code>
     */
    //TODO nick - refactor - remove this method?
    public static String getAjaxOncomplete(UIComponent component) {
        if (component instanceof AjaxComponent) {
            return ((AjaxComponent) component).getOncomplete();
        }

        return (String) component.getAttributes().get(ONCOMPLETE_ATTR_NAME);
    }

    /**
     * Get function name for call before update DOM.
     *
     * @param component for wich calculate function name
     * @return name of JavaScript function or <code>null</code>
     */
    //TODO nick - refactor - remove this method?
    public static String getAjaxOnBeforeDomUpdate(UIComponent component) {
        if (component instanceof AjaxComponent) {
            return ((AjaxComponent) component).getOnbeforedomupdate();
        }

        return (String) component.getAttributes().get(ONBEFOREDOMUPDATE_ATTR_NAME);
    }

    /**
     * @param component
     * @return
     * @since 4.0
     */
    public static Object getAjaxData(UIComponent component) {
        if (component instanceof AjaxComponent) {
            return ((AjaxComponent) component).getData();
        }

        return component.getAttributes().get(DATA_ATTR_NAME);
    }

}
