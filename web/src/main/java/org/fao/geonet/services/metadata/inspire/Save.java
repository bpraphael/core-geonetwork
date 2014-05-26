package org.fao.geonet.services.metadata.inspire;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import jeeves.interfaces.Service;
import jeeves.resources.dbms.Dbms;
import jeeves.server.ServiceConfig;
import jeeves.server.context.ServiceContext;
import jeeves.utils.Util;
import jeeves.utils.Xml;
import jeeves.xlink.Processor;
import org.apache.jcs.access.exception.CacheException;
import org.fao.geonet.GeonetContext;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.constants.Params;
import org.fao.geonet.kernel.AddElemValue;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.EditLib;
import org.fao.geonet.kernel.SchemaManager;
import org.fao.geonet.kernel.reusable.ReusableObjManager;
import org.fao.geonet.kernel.schema.MetadataSchema;
import org.fao.geonet.kernel.schema.MetadataType;
import org.fao.geonet.kernel.search.spatial.Pair;
import org.fao.geonet.languages.IsoLanguagesMapper;
import org.fao.geonet.services.metadata.AjaxEditUtils;
import org.fao.geonet.util.XslUtil;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fao.geonet.constants.Geonet.Namespaces.GCO;
import static org.fao.geonet.constants.Geonet.Namespaces.GEONET;
import static org.fao.geonet.constants.Geonet.Namespaces.GMD;
import static org.fao.geonet.constants.Geonet.Namespaces.SRV;
import static org.fao.geonet.constants.Geonet.Namespaces.XLINK;
import static org.fao.geonet.util.XslUtil.CHE_NAMESPACE;

/**
 * @author Jesse on 5/17/2014.
 */
public class Save implements Service {
    static final List<Namespace> NS = Arrays.asList(
            GCO,
            GMD,
            Geonet.Namespaces.XLINK,
            XslUtil.CHE_NAMESPACE);

    private static final String PARAM_DATA = "data";
    public static final String EL_CHARACTER_STRING = "CharacterString";
    public static final String ATT_CODE_LIST_VALUE = "codeListValue";
    public static final String ATT_CODE_LIST = "codeList";

    static final String JSON_IDENTIFICATION_ABSTRACT = "abstract";
    static final String JSON_CONTACT_ID = "id";
    static final String JSON_CONTACT = "contact";
    static final String JSON_OTHER_LANGUAGES = "otherLanguages";
    static final String JSON_LANGUAGE = "language";
    static final String JSON_HIERARCHY_LEVEL = "hierarchyLevel";
    static final String JSON_VALIDATED = "validated";
    static final String JSON_CONTACT_ROLE = "role";
    static final String JSON_CONTACT_FIRST_NAME = "name";
    static final String JSON_CONTACT_LAST_NAME = "surname";
    static final String JSON_CONTACT_EMAIL = "email";
    static final String JSON_IDENTIFICATION = "identification";
    static final String JSON_IDENTIFICATION_TYPE = "type";
    static final String JSON_CONTACT_ORG_NAME = "organization";
    static final String JSON_IDENTIFICATION_DATE = "date";
    static final String JSON_IDENTIFICATION_DATE_TYPE = "dateType";
    static final String JSON_IDENTIFICATION_IDENTIFIER = "citationIdentifier";
    static final String JSON_IDENTIFICATION_POINT_OF_CONTACT = "pointOfContact";
    static final String JSON_IDENTIFICATION_TYPE_DATA_VALUE = "data";
    static final String JSON_HIERARCHY_LEVEL_NAME = "hierarchyLevelName";
    static final String JSON_IDENTIFICATION_TOPIC_CATEGORIES = "topicCategory";
    static final String JSON_CONSTRAINTS = "constraints";
    static final String JSON_CONSTRAINTS_LEGAL = "legal";
    static final String JSON_CONSTRAINTS_GENERIC = "generic";
    static final String JSON_CONSTRAINTS_SECURITY = "security";
    static final String JSON_CHARACTER_SET = "characterSet";
    public static final String JSON_IDENTIFICATION_TITLE = "title";
    public static final String JSON_IDENTIFICATION_DATE_TAG_NAME = "dateTagName";
    public static final String JSON_IDENTIFICATION_KEYWORDS = "descriptiveKeywords";
    public static final String JSON_IDENTIFICATION_KEYWORD_CODE = "code";
    public static final String JSON_IDENTIFICATION_KEYWORD_WORD = "word";
    public static final String JSON_IDENTIFICATION_EXTENTS = "extents";
    public static final String JSON_CONSTRAINTS_ACCESS_CONSTRAINTS = "accessConstraints";
    public static final String JSON_CONSTRAINTS_USE_CONSTRAINTS = "useConstraints";
    public static final String JSON_CONSTRAINTS_OTHER_CONSTRAINTS = "otherConstraints";
    public static final String JSON_CONSTRAINTS_USE_LIMITATIONS = "useLimitations";
    public static final String JSON_IDENTIFICATION_EXTENT_GEOM = "geom";
    public static final String JSON_IDENTIFICATION_EXTENT_DESCRIPTION = "description";
    static final String JSON_CONSTRAINTS_LEGISLATION_CONSTRAINTS = "legislationConstraints";

    @Override
    public void init(String appPath, ServiceConfig params) throws Exception {

    }

    @Override
    public Element exec(Element params, ServiceContext context) throws Exception {
        final String data = Util.getParam(params, PARAM_DATA);
        final String id = params.getChildText(Params.ID);

        GeonetContext handlerContext = (GeonetContext) context.getHandlerContext(Geonet.CONTEXT_NAME);
        final SchemaManager schemaManager = handlerContext.getSchemamanager();
        final DataManager dataManager = handlerContext.getDataManager();
        final EditLib editLib = new EditLib(schemaManager);

        final AjaxEditUtils ajaxEditUtils = getAjaxEditUtils(params, context);
        MetadataSchema metadataSchema = schemaManager.getSchema("iso19139.che");
        final Element metadata = getMetadata(context, id, ajaxEditUtils);

        final JSONObject jsonObject = new JSONObject(data);

        updateMetadata(context, editLib, metadata, metadataSchema, jsonObject);

        final Element identificationInfo = updateIdentificationInfo(null, editLib, metadata, metadataSchema, jsonObject);
        updateConstraints(editLib, metadataSchema, identificationInfo, jsonObject);

        Element metadataToSave = (Element) metadata.clone();
        editLib.removeEditingInfo(metadataToSave);
        saveMetadata(context, id, dataManager, metadataToSave);

        return new Element("ok");
    }

    private void updateConstraints(EditLib editLib, MetadataSchema metadataSchema, Element identificationInfo,
                                   JSONObject jsonObject) throws JSONException, JDOMException {
        JSONObject constraintsJson = jsonObject.optJSONObject(JSON_CONSTRAINTS);
        if (constraintsJson == null) {
            return;
        }

        List<String> refsOfConstraintsToKeep = Lists.newArrayList();

        final JSONArray legalConstraints;
        if (constraintsJson.has(JSON_CONSTRAINTS_LEGAL)) {
            legalConstraints = constraintsJson.optJSONArray(JSON_CONSTRAINTS_LEGAL);
        } else {
            legalConstraints = new JSONArray();
        }
        addReferences(legalConstraints, refsOfConstraintsToKeep);
        addReferences(constraintsJson.optJSONArray(JSON_CONSTRAINTS_GENERIC), refsOfConstraintsToKeep);
        addReferences(constraintsJson.optJSONArray(JSON_CONSTRAINTS_SECURITY), refsOfConstraintsToKeep);

        ArrayList<Object> allConstraints = Lists.newArrayList(Xml.selectNodes(identificationInfo,
                "gmd:resourceConstraints/node()[@gco:isoType = 'gmd:MD_LegalConstraints' or name() = 'gmd:MD_LegalConstraints' " +
                "or name() = 'che:CHE_MD_LegalConstraints' or name() = 'gmd:MD_Constraints' or @gco:isoType = 'gmd:MD_Constraints'" +
                "or name() = 'gmd:MD_SecurityConstraints' or @gco:isoType = 'gmd:MD_SecurityConstraints']", NS));

        for (Object o : allConstraints) {
            Element el = (Element) o;
            String ref = el.getChild("element", GEONET).getAttributeValue(Params.REF);
            if (!refsOfConstraintsToKeep.contains(ref)) {
                el.getParentElement().detach();
            }
        }
        int addIndex = -1;
        Element resourceConstraints = identificationInfo.getChild("resourceConstraints", GMD);
        if (resourceConstraints != null) {
            addIndex = identificationInfo.indexOf(resourceConstraints);
        }

        for (int i = 0; i < legalConstraints.length(); i++) {
            final JSONObject constraint = legalConstraints.getJSONObject(i);

            String ref = constraint.optString(Params.REF, "");
            Element legalConstraintEl = null;

            if (!ref.trim().isEmpty()) {
                legalConstraintEl = Xml.selectElement(identificationInfo,
                        "gmd:resourceConstraints/node()[geonet:element/@ref = '" + ref + "']", NS);
                if (legalConstraintEl != null && legalConstraintEl.getParentElement() != null) {
                    legalConstraintEl.getParentElement().detach();
                }
            }

            if (legalConstraintEl == null) {
                legalConstraintEl = new Element("CHE_MD_LegalConstraints", CHE_NAMESPACE).setAttribute("isoType", "gmd:MD_LegalConstraints", GCO);
            }


            updateMultipleTranslatedInstances(editLib, metadataSchema, legalConstraintEl, "gmd:useLimitation", constraint,
                    "useLimitation", JSON_CONSTRAINTS_USE_LIMITATIONS);

            updateConstraintCodeList(editLib, metadataSchema, legalConstraintEl, "gmd:accessConstraints", constraint,
                    JSON_CONSTRAINTS_ACCESS_CONSTRAINTS);
            updateConstraintCodeList(editLib, metadataSchema, legalConstraintEl, "gmd:useConstraints", constraint,
                    JSON_CONSTRAINTS_USE_CONSTRAINTS);

            updateMultipleTranslatedInstances(editLib, metadataSchema, legalConstraintEl, "gmd:otherConstraints", constraint,
                    JSON_CONSTRAINTS_OTHER_CONSTRAINTS, JSON_CONSTRAINTS_OTHER_CONSTRAINTS);
            updateMultipleTranslatedInstances(editLib, metadataSchema, legalConstraintEl,
                    "che:legislationConstraints/che:CHE_MD_Legislation/che:title/gmd:CI_Citation/gmd:title", constraint,
                    "title", JSON_CONSTRAINTS_LEGISLATION_CONSTRAINTS);

            resourceConstraints = legalConstraintEl.getParentElement();
            if (resourceConstraints == null) {
                resourceConstraints = new Element("resourceConstraints", GMD).addContent(legalConstraintEl);
            }

            if (addIndex == -1) {
                assert editLib.addElementOrFragmentFromXpath(identificationInfo, metadataSchema, "gmd:resourceConstraints",
                        new AddElemValue(resourceConstraints), true);
                addIndex = identificationInfo.indexOf(resourceConstraints);
            } else {
                identificationInfo.addContent(addIndex, resourceConstraints);
            }

            addIndex++;

        }

    }

    private void updateMultipleTranslatedInstances(EditLib editLib, MetadataSchema metadataSchema, Element metadata, String xpath,
                                                   JSONObject constraint, String tagName, String jsonKey)
            throws JSONException, JDOMException {
        final JSONArray useLimitations = constraint.getJSONArray(jsonKey);

        Pair<Integer, Element> result = removeOldElements(metadata, xpath);
        int addIndex = result.one();
        Element parentElement = result.two();

        for (int j = 0; j < useLimitations.length(); j++) {
            Element translation = createTranslatedInstance(useLimitations.getJSONObject(j), tagName, GMD);
            if (addIndex == -1) {
                assert editLib.addElementOrFragmentFromXpath(metadata, metadataSchema, xpath,
                        new AddElemValue(translation), true);
                addIndex = translation.getParentElement().indexOf(translation);
                parentElement = translation.getParentElement();
            } else {
                parentElement.addContent(addIndex, translation);
            }
            addIndex++;
        }
    }

    private void updateConstraintCodeList(EditLib editLib, MetadataSchema metadataSchema, Element metadata, String xpath, JSONObject
            constraint, String tagName) throws JSONException, JDOMException {
        final JSONArray codeListValues = constraint.optJSONArray(tagName);

        Pair<Integer, Element> result = removeOldElements(metadata, xpath);
        int addIndex = result.one();
        Element parentElement = result.two();

        for (int j = 0; j < codeListValues.length(); j++) {
            String value = codeListValues.getString(j);
            Element element = new Element(tagName, GMD).addContent(
                    createCodeListEl("MD_RestrictionCode", GMD,
                            "http://www.isotc211.org/2005/resources/codeList" +
                                                                ".xml#MD_RestrictionCode", value));
            if (addIndex == -1) {
                assert editLib.addElementOrFragmentFromXpath(metadata, metadataSchema, xpath,
                        new AddElemValue(element), true);
                addIndex = element.getParentElement().indexOf(element);
                parentElement = element.getParentElement();
            } else {
                parentElement.addContent(addIndex, element);
            }
            addIndex++;
        }
    }

    private Pair<Integer, Element> removeOldElements(Element metadata, String xpath) throws JDOMException {

        final List<?> elementsToReplace = Lists.newArrayList(Xml.selectNodes(metadata, xpath, NS));

        int addIndex = -1;
        Element parentElement = null;

        if (!elementsToReplace.isEmpty()) {
            Element elem = (Element) elementsToReplace.get(0);
            parentElement = elem.getParentElement();
            addIndex = parentElement.indexOf(elem);
            for (Object o : elementsToReplace) {
                elem = (Element) o;
                elem.detach();
            }
        }

        return Pair.read(addIndex, parentElement);
    }

    private Element createCodeListEl(String tagName, Namespace namespace, String codeList, String codeListValue) {
        return new Element(tagName, namespace).
                setAttribute(ATT_CODE_LIST, codeList).
                setAttribute(ATT_CODE_LIST_VALUE, codeListValue);
    }

    private void addReferences(JSONArray jsonArray, List<String> refsOfConstraintsToKeep) throws JSONException {
        if (jsonArray != null) {
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject constraint = jsonArray.getJSONObject(i);
                if (!constraint.optString(Params.REF, "").trim().isEmpty()) {
                    refsOfConstraintsToKeep.add(constraint.getString(Params.REF));
                }
            }
        }
    }

    protected void updateMetadata(ServiceContext context, EditLib editLib, Element metadata, MetadataSchema metadataSchema, JSONObject
            jsonObject) throws Exception {
        updateCharString(editLib, metadata, metadataSchema, "gmd:language", jsonObject.optString(JSON_LANGUAGE));
        updateCharString(editLib, metadata, metadataSchema, "gmd:hierarchyLevelName", jsonObject.optString(JSON_HIERARCHY_LEVEL_NAME));

        updateCharset(editLib, metadata, metadataSchema, jsonObject);
        updateHierarchyLevel(editLib, metadata, metadataSchema, jsonObject);
        updateContact(editLib, metadataSchema, metadata, "gmd:contact", jsonObject.optJSONArray("contact"), context);
        assert editLib.addElementOrFragmentFromXpath(metadata, metadataSchema, "gmd:dateStamp/gco:DateTime", new AddElemValue("2014-05-20T07:35:05"), true);
        updateOtherLanguage(editLib, metadata, metadataSchema, jsonObject);
    }

    protected Element updateIdentificationInfo(ServiceContext context, EditLib editLib, Element metadata,
                                               MetadataSchema metadataSchema, JSONObject jsonObject) throws Exception {
        JSONObject identificationJson = jsonObject.optJSONObject(JSON_IDENTIFICATION);
        if (identificationJson == null) {
            identificationJson = new JSONObject();
        }
        Element identification = getIdentification(editLib, metadata, metadataSchema, identificationJson);

        updateTranslatedInstance(editLib, metadataSchema, identification, identificationJson.optJSONObject("title"),
                "gmd:citation/gmd:CI_Citation/gmd:title", JSON_IDENTIFICATION_TITLE, GMD);

        updateDate(editLib, metadataSchema, identification, identificationJson);
        JSONObject citationIdentifier = identificationJson.optJSONObject(JSON_IDENTIFICATION_IDENTIFIER);

        updateTranslatedInstance(editLib, metadataSchema, identification, citationIdentifier,
                "gmd:citation/gmd:CI_Citation/gmd:identifier/gmd:MD_Identifier/gmd:code", JSON_IDENTIFICATION_KEYWORD_CODE, GMD);

        updateTranslatedInstance(editLib, metadataSchema, identification, identificationJson.optJSONObject(JSON_IDENTIFICATION_ABSTRACT),
                "gmd:abstract", "abstract", GMD);

        updateContact(editLib, metadataSchema, identification, "gmd:pointOfContact", identificationJson.optJSONArray
                (JSON_IDENTIFICATION_POINT_OF_CONTACT), context);

        updateCharString(editLib, identification, metadataSchema, "gmd:language", identificationJson.optString(JSON_LANGUAGE));

        updateKeywords(context, editLib, metadataSchema, identification, identificationJson);
        updateTopicCategory(editLib, metadataSchema, identification, identificationJson);
        updateExtent(context, editLib, metadataSchema, identification, identificationJson);
        return identification;
    }

    private void updateTopicCategory(EditLib editLib, MetadataSchema metadataSchema, Element identification, JSONObject identificationJson) throws Exception {


        int insertIndex = findIndexToAddElements(editLib, identification, metadataSchema, "gmd:topicCategory").one();

        JSONArray categories = identificationJson.optJSONArray(JSON_IDENTIFICATION_TOPIC_CATEGORIES);
        if (categories != null) {
            for (int i = 0; i < categories.length(); i++) {
                String category = categories.getString(i);
                final Element element = new Element("topicCategory", GMD).addContent(new Element("MD_TopicCategoryCode",
                        GMD).setText(category));

                identification.addContent(insertIndex + i, element);
            }
        }
    }

    private void updateKeywords(ServiceContext context, EditLib editLib, MetadataSchema metadataSchema, Element identification,
                                JSONObject identificationJson) throws Exception {
        HrefBuilder hrefBuilder = new KeywordHrefBuilder();

        updateSharedObject(context, editLib, metadataSchema, identification, identificationJson, JSON_IDENTIFICATION_KEYWORDS,
                JSON_IDENTIFICATION_KEYWORDS, GMD, hrefBuilder);
    }
    private void updateExtent(ServiceContext context, EditLib editLib, MetadataSchema metadataSchema, Element identification,
                              JSONObject identificationJson) throws Exception {
        HrefBuilder hrefBuilder = new ExtentHrefBuilder();

        Namespace namespace;
        if (identificationJson.optString(JSON_IDENTIFICATION_TYPE, "data").equals(JSON_IDENTIFICATION_TYPE_DATA_VALUE)) {
            namespace = GMD;
        } else {
            namespace = SRV;
        }
        updateSharedObject(context, editLib, metadataSchema, identification, identificationJson, JSON_IDENTIFICATION_EXTENTS,
                "extent", namespace, hrefBuilder);
    }

    private void updateSharedObject(ServiceContext context, EditLib editLib, MetadataSchema metadataSchema, Element identification,
                                    JSONObject identificationJson, String jsonKey, String tagName, Namespace namespace, HrefBuilder hrefBuilder) throws Exception {
        final JSONArray jsonObjects = identificationJson.optJSONArray(jsonKey);

        if (jsonObjects == null) {
            return;
        }

        Set<String> hrefsToKeep = Sets.newHashSet();
        Map<String, Element> hrefsToAdd = Maps.newIdentityHashMap();

        for (int i = 0; i < jsonObjects.length(); i++) {
            JSONObject jsonObject = jsonObjects.getJSONObject(i);

            final String href = hrefBuilder.createHref(jsonObject, identification, identificationJson.getString(JSON_IDENTIFICATION_TYPE));
            hrefsToKeep.add(href);
            hrefsToAdd.put(href, null);
        }

        @SuppressWarnings("unchecked")
        final List<Element> elementList = Lists.newArrayList(identification.getChildren(tagName, namespace));

        int addIndex;
        if (elementList.isEmpty()) {
            final Element element = new Element(tagName, namespace);
            assert editLib.addElementOrFragmentFromXpath(identification, metadataSchema, namespace.getPrefix() + ":" + tagName,
                    new AddElemValue(element), true);
            addIndex = identification.indexOf(element);
            element.detach();
        } else {
            addIndex = identification.indexOf(elementList.get(0));
            for (Element sharedObjectEl : elementList) {
                String href = sharedObjectEl.getAttributeValue("href", XLINK);

                if (hrefsToKeep.contains(href)) {
                    hrefsToAdd.put(href, sharedObjectEl);
                } else {
                    elementList.remove(sharedObjectEl);
                }

                sharedObjectEl.detach();
            }
        }

        for (Map.Entry<String, Element> entry : hrefsToAdd.entrySet()) {
            String href = entry.getKey();
            final Element element;
            if (entry.getValue() == null) {
                element = new Element(tagName, namespace).
                        setAttribute("href", href, XLINK).
                        addContent(resolveXlink(context, href));
                entry.setValue(element);
            }
        }

        identification.addContent(addIndex, hrefsToAdd.values());
    }

    private void updateDate(EditLib editLib, MetadataSchema metadataSchema, Element identification, JSONObject identificationJson) throws JSONException {
        final String jsonDate = identificationJson.optString(JSON_IDENTIFICATION_DATE);
        final String jsonDateType = identificationJson.optString(JSON_IDENTIFICATION_DATE_TYPE, "creation");
        if (jsonDate != null) {
            final Element dateEl;
            if (jsonDate.contains("T")) {
                dateEl = new Element("DateTime", GCO);
            } else {
                dateEl = new Element("Date", GCO);
            }
            dateEl.setText(jsonDate);

            final Element date = new Element("date", GMD).addContent(
                    new Element("CI_Date", GMD).addContent(Arrays.asList(
                            new Element("date", GMD).addContent(dateEl),
                            new Element("dateType", GMD).addContent(
                                    createCodeListEl("CI_DateTypeCode", GMD,
                                            "http://www.isotc211.org/2005/resources/codeList.xml#CI_DateTypeCode", jsonDateType)
                            )
                    ))
            );
            assert editLib.addElementOrFragmentFromXpath(identification, metadataSchema, "gmd:citation/gmd:CI_Citation/gmd:date",
                    new AddElemValue(date), true);
        }
    }

    private Element getIdentification(EditLib editLib, Element metadata, MetadataSchema metadataSchema,
                                      JSONObject identificationJson) throws Exception {
        Element identificationInfo = metadata.getChild("identificationInfo", Geonet.Namespaces.GMD);
        if (identificationInfo == null) {
            identificationInfo = editLib.addElement(metadataSchema.getName(), metadata, "gmd:identificationInfo");
        }

        final String requiredInfoTagName;
        final String isoType;
        final String jsonType = identificationJson.optString(JSON_IDENTIFICATION_TYPE, "data");
        if (jsonType.equalsIgnoreCase("data")) {
            requiredInfoTagName = "CHE_MD_DataIdentification";
            isoType = "gmd:MD_DataIdentification";
        } else {
            requiredInfoTagName = "CHE_SV_ServiceIdentification";
            isoType = "srv:SV_ServiceIdentification";
        }

        Element finalInfo;
        if (!identificationInfo.getChildren().isEmpty()) {
            finalInfo = (Element) identificationInfo.getChildren().get(0);
        } else {
            finalInfo = new Element(requiredInfoTagName, CHE_NAMESPACE).setAttribute("isoType", isoType, GCO);
            identificationInfo.addContent(finalInfo);
        }

        if (!finalInfo.getName().equals(requiredInfoTagName)) {
            finalInfo.detach();

            Element newInfo = new Element(requiredInfoTagName, CHE_NAMESPACE);
            identificationInfo.addContent(newInfo);

            final MetadataType elementType = metadataSchema.getTypeInfo("che:" + requiredInfoTagName);
            final List<String> elementList = elementType.getElementList();
            for (Object o : finalInfo.getChildren()) {
                Element child = (Element) o;
                String name = child.getNamespacePrefix() + ":" + child.getName();
                if (elementList.contains(name)) {
                    finalInfo.addContent(child);
                }
            }
        }

        return finalInfo;
    }

    private void updateOtherLanguage(EditLib editLib, Element metadata, MetadataSchema metadataSchema,
                                     JSONObject jsonObject) throws Exception {
        String tagName = "gmd:locale";
        int insertIndex = findIndexToAddElements(editLib, metadata, metadataSchema, tagName).one();

        JSONArray languages = jsonObject.optJSONArray(JSON_OTHER_LANGUAGES);
        if (languages != null) {
            for (int i = 0; i < languages.length(); i++) {
                String language = languages.getString(i);
                Element localeEl = new Element("locale", GMD).addContent(
                        new Element("PT_Locale", GMD).setAttribute("id", getIsoLanguagesMapper().iso639_2_to_iso639_1(language).toUpperCase

                                ()).
                                addContent(Arrays.asList(
                                        new Element("languageCode", GMD).addContent(
                                                new Element("LanguageCode", GMD).
                                                        setAttribute(ATT_CODE_LIST_VALUE, language).
                                                        setAttribute(ATT_CODE_LIST, "#LanguageCode")
                                        ),
                                        new Element("characterEncoding", GMD).addContent(
                                                new Element("MD_CharacterSetCode", GMD).
                                                        setAttribute(ATT_CODE_LIST_VALUE, "utf8").
                                                        setAttribute(ATT_CODE_LIST, "#MD_CharacterSetCode").
                                                        setText("UTF8")
                                        )
                                ))
                );

                metadata.addContent(insertIndex + i, localeEl);
            }
        }
    }

    private Pair<Integer, Element> findIndexToAddElements(EditLib editLib, Element metadata, MetadataSchema metadataSchema, String tagName) throws Exception {
        @SuppressWarnings("unchecked")
        List<Element> previousEls = Lists.newArrayList((List<Element>) Xml.selectNodes(metadata, tagName,
                metadataSchema.getSchemaNS()));

        if (previousEls.isEmpty()) {
            // if there are no contacts then we need to add one to know where to insert all of the new ones.
            Element newEl = editLib.addElement(metadataSchema.getName(), metadata, tagName);
            previousEls = Arrays.asList(newEl);
        }

        final Element parentElement = previousEls.get(0).getParentElement();
        int insertIndex = parentElement.indexOf(previousEls.get(0));

        for (Element previousEl : previousEls) {
            previousEl.detach();
        }
        return Pair.read(insertIndex, parentElement);
    }

    private void updateCharString(EditLib editLib, Element metadata, MetadataSchema metadataSchema, String xpath, String newValue) {
        if (newValue != null) {
            Element langEl = new Element(EL_CHARACTER_STRING, GCO).setText(newValue);
            assert editLib.addElementOrFragmentFromXpath(metadata, metadataSchema, xpath, new AddElemValue(langEl), true);
        }
    }

    private void updateCharset(EditLib editLib, Element metadata, MetadataSchema metadataSchema,
                               JSONObject jsonObject) throws JSONException {
        final String charset = jsonObject.optString(JSON_CHARACTER_SET);
        if (!Strings.isNullOrEmpty(charset)) {
            Element codeElement = new Element("MD_CharacterSetCode", GMD).setAttribute(ATT_CODE_LIST_VALUE, charset).
                    setAttribute(ATT_CODE_LIST, "http://www.isotc211.org/2005/resources/codeList.xml#MD_CharacterSetCode");
            assert editLib.addElementOrFragmentFromXpath(metadata, metadataSchema, "gmd:characterSet", new AddElemValue(codeElement), true);
        }
    }

    private void updateHierarchyLevel(EditLib editLib, Element metadata, MetadataSchema metadataSchema,
                                      JSONObject jsonObject) throws JSONException {
        final String hierarchyLevel = jsonObject.optString(JSON_HIERARCHY_LEVEL);
        if (!Strings.isNullOrEmpty(hierarchyLevel)) {
            Element hierarchyLevelEl = new Element("MD_ScopeCode", GMD).setAttribute(ATT_CODE_LIST_VALUE, hierarchyLevel).
                    setAttribute(ATT_CODE_LIST, "http://www.isotc211.org/2005/resources/codeList.xml#MD_ScopeCode");
            assert editLib.addElementOrFragmentFromXpath(metadata, metadataSchema, "gmd:hierarchyLevel", new AddElemValue(hierarchyLevelEl), true);

        }
    }

    private void updateContact(EditLib editLib, MetadataSchema metadataSchema, Element metadata, String xpath,
                               JSONArray contacts, ServiceContext context) throws Exception {
        if (contacts == null) {
            return;
        }

        String tagName = xpath;
        if (xpath.contains("/")) {
            tagName = xpath.substring(xpath.indexOf("/") + 1);
        }
        Pair<Integer, Element> result = findIndexToAddElements(editLib, metadata, metadataSchema, tagName);

        int insertIndex = result.one();

        for (int i = 0; i < contacts.length(); i++) {
            JSONObject contact = contacts.getJSONObject(i);
            String contactId = contact.optString(JSON_CONTACT_ID, null);
            boolean validated = contact.getBoolean(JSON_VALIDATED);
            String role = contact.getString(JSON_CONTACT_ROLE);

            String xlinkHref = "local://xml.user.get?id=" + contactId + "&amp;schema=iso19139.che&amp;role=" + role;

            final Element contactEl;
            if (validated && !Strings.isNullOrEmpty(contactId)) {
                contactEl = new Element(tagName.split(":", 2)[1], GMD).
                        setAttribute("href", xlinkHref, XLINK).
                        setAttribute("show", "embed", XLINK);

                contactEl.addContent(resolveXlink(context, xlinkHref));
            } else {
                contactEl = new Element(tagName.split(":", 2)[1], GMD);
                if (!Strings.isNullOrEmpty(contactId)) {
                    contactEl.setAttribute("href", xlinkHref, XLINK).
                            setAttribute("show", "embed", XLINK).
                            setAttribute("role", ReusableObjManager.NON_VALID_ROLE, XLINK);

                    contactEl.addContent(resolveXlink(context, xlinkHref));
                }

                Element emailEl = new Element("electronicMailAddress", GMD).addContent(
                        new Element("CharacterString", GCO).setText(contact.getString(JSON_CONTACT_EMAIL))
                );

                Element firstNameEl = new Element("individualFirstName", CHE_NAMESPACE).addContent(
                        new Element(EL_CHARACTER_STRING, GCO).setText(contact.getString(JSON_CONTACT_FIRST_NAME)));
                Element lastNameEl = new Element("individualLastName", CHE_NAMESPACE).addContent(
                        new Element(EL_CHARACTER_STRING, GCO).setText(contact.getString(JSON_CONTACT_LAST_NAME)));
                Element roleEl = new Element("role", GMD).addContent(
                        new Element("CI_RoleCode", GMD).
                                setAttribute(ATT_CODE_LIST_VALUE, role).
                                setAttribute(ATT_CODE_LIST, "http://www.isotc211.org/2005/resources/codeList.xml#CI_RoleCode")
                );

                updateTranslatedInstance(editLib, metadataSchema, contactEl, contact.getJSONObject(JSON_CONTACT_ORG_NAME),
                        "che:CHE_CI_ResponsibleParty/gmd:organisationName", "organisationName", GMD);
                assert editLib.addElementOrFragmentFromXpath(contactEl, metadataSchema,
                        "che:CHE_CI_ResponsibleParty/gmd:contactInfo/gmd:CI_Contact/gmd:address/che:CHE_CI_Address",
                        new AddElemValue(emailEl),
                        true);
                assert editLib.addElementOrFragmentFromXpath(contactEl, metadataSchema,
                        "che:CHE_CI_ResponsibleParty/che:individualFirstName",
                        new AddElemValue(firstNameEl),
                        true);
                assert editLib.addElementOrFragmentFromXpath(contactEl, metadataSchema,
                        "che:CHE_CI_ResponsibleParty/che:individualLastName",
                        new AddElemValue(lastNameEl),
                        true);
                assert editLib.addElementOrFragmentFromXpath(contactEl, metadataSchema,
                        "che:CHE_CI_ResponsibleParty/gmd:role",
                        new AddElemValue(roleEl),
                        true);
            }

            result.two().addContent(insertIndex + i, contactEl);
        }
    }

    private void updateTranslatedInstance(EditLib editLib, MetadataSchema metadataSchema, Element metadata, JSONObject translationJson,
                                          String xpath, String tagName, Namespace namespace) throws JSONException {
        if (translationJson != null) {
            Element translatedEl = createTranslatedInstance(translationJson, tagName, namespace);
            assert editLib.addElementOrFragmentFromXpath(metadata, metadataSchema, xpath, new AddElemValue(translatedEl), true);
        }
    }
    private Element createTranslatedInstance(JSONObject translationJson,
                                             String tagName, Namespace namespace) throws JSONException {
        IsoLanguagesMapper instance = getIsoLanguagesMapper();
        List<Element> translations = Lists.newArrayList();
        final Iterator keys = translationJson.keys();
        while (keys.hasNext()) {
            String threeLetterLangCode = (String) keys.next();
            String translation = translationJson.getString(threeLetterLangCode);

            translations.add(new Element("textGroup", GMD).addContent(
                    new Element("LocalisedCharacterString", GMD).
                            setAttribute("locale", "#" + instance.iso639_2_to_iso639_1(threeLetterLangCode).toUpperCase()).
                            setText(translation)
            ));

        }
        return new Element(tagName, namespace).addContent(
                new Element("PT_FreeText", GMD).addContent(translations)
        );
    }

    @VisibleForTesting
    protected void saveMetadata(ServiceContext context, String id, DataManager dataManager, Element metadata) throws Exception {
        Dbms dbms = (Dbms) context.getResourceManager().open(Geonet.Res.MAIN_DB);
        dataManager.updateMetadata(context, dbms, id, metadata, false, true, true, context.getLanguage(), null,
                true, true);
    }

    @VisibleForTesting
    protected Element getMetadata(ServiceContext context, String id, AjaxEditUtils ajaxEditUtils) throws Exception {
        Element metadata = (Element) context.getUserSession().getProperty(Geonet.Session.METADATA_EDITING + id);
        if (metadata == null) {
            metadata = ajaxEditUtils.getMetadataEmbedded(context, id, true, false);
        }
        return metadata;
    }

    @VisibleForTesting
    protected IsoLanguagesMapper getIsoLanguagesMapper() {
        return IsoLanguagesMapper.getInstance();
    }

    @VisibleForTesting
    protected Element resolveXlink(ServiceContext context, String xlinkHref) throws IOException, JDOMException, CacheException {
        return Processor.resolveXLink(xlinkHref, context);
    }

    @VisibleForTesting
    protected AjaxEditUtils getAjaxEditUtils(Element params, ServiceContext context) throws Exception {
        final AjaxEditUtils ajaxEditUtils = new AjaxEditUtils(context);
        ajaxEditUtils.preprocessUpdate(params, context);
        return ajaxEditUtils;
    }

    private interface HrefBuilder {
        public String createHref(JSONObject jsonObject, Element elem, String identType) throws Exception;
    }

    private static class KeywordHrefBuilder implements HrefBuilder {
        @Override
        public String createHref(JSONObject jsonObject, Element elem, String identType) throws Exception {

            String hrefTemplate = "local://che.keyword.get?thesaurus=%s&amp;id=%s&amp;locales=fr,en,de,it";

            String thesaurus;
            if (identType.equals(JSON_IDENTIFICATION_TYPE_DATA_VALUE)) {
                thesaurus = "external.theme.inspire-theme";
            } else {
                thesaurus = "external.theme.inspire-service-taxonomy";
            }

            final String code = URLEncoder.encode(jsonObject.getString("code"), "UTF-8");
            return String.format(hrefTemplate, thesaurus, code);
        }
    }

    private static class ExtentHrefBuilder implements HrefBuilder {
        Map<String, String> typenameMapping = Maps.newHashMap();

        {
            typenameMapping.put("kantone", "kantoneBB");
            typenameMapping.put("gemeinden", "gemeindenBB");
            typenameMapping.put("countries", "countries");
        }

        @Override
        public String createHref(JSONObject jsonObject, Element elem, String identType) throws Exception {

            String hrefTemplate = "local://xml.extent.get?id=%s&amp;wfs=default&amp;typename=gn:%s&amp;format=gmd_complete&amp;extentTypeCode=true";

            final String[] geom = jsonObject.getString("geom").split(":");
            String id = geom[1];
            String typename = typenameMapping.get(geom[0]);

            return String.format(hrefTemplate, id, typename);
        }
    }
}
