/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.phenotips.studies.family.internal;

import org.phenotips.studies.family.Pedigree;

import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * @version $Id$
 */
public class DefaultPedigree implements Pedigree
{
    /** The main key under which pedigree data is stored. */
    private static final String PEDIGREE_JSON_DATA_KEY = "GG";

    private static final String PEDIGREE_JSON_PROBAND_KEY = "probandNodeID";

    /** The name under which the linked patient id resides under in the JSON generated by the pedigree. */
    private static final String PATIENT_LINK_JSON_KEY = "phenotipsId";

    private static final String PATIENT_LASTNAME_JSON_KEY = "lName";

    private static final String ID_JSON_KEY = "id";

    private static final String PROP_JSON_KEY = "prop";

    private static final String CONSANGUINITY_JSON_KEY = "consanguinity";

    private static final String FAMILY_HISTORY_JSON_KEY = "family_history";

    private JSONObject data;

    private String image = "";

    /**
     * Create a new default pedigree with data and image.
     *
     * @param data pedigree data
     * @param image SVG 'image'
     */
    public DefaultPedigree(JSONObject data, String image)
    {
        if (data == null || data.length() == 0) {
            throw new IllegalArgumentException();
        }
        this.data = data;
        this.image = image;
    }

    @Override
    public JSONObject getData()
    {
        return this.data;
    }

    @Override
    public String getImage(String highlightCurrentPatientId)
    {
        return getImage(highlightCurrentPatientId, 0, 0);
    }

    @Override
    public String getImage(String highlightCurrentPatientId, int width, int height)
    {
        String svg = SvgUpdater.setCurrentPatientStylesInSvg(this.image, highlightCurrentPatientId);
        if (width > 0) {
            svg = SvgUpdater.setSVGWidth(svg, width);
        }
        if (height > 0) {
            svg = SvgUpdater.setSVGHeight(svg, height);
        }
        return svg;
    }

    @Override
    public List<String> extractIds()
    {
        List<String> extractedIds = new LinkedList<>();
        for (JSONObject properties : this.extractPatientJSONProperties()) {
            Object id = properties.get(DefaultPedigree.PATIENT_LINK_JSON_KEY);
            extractedIds.add(id.toString());
        }
        return extractedIds;
    }

    @Override
    public List<JSONObject> extractPatientJSONProperties()
    {
        List<JSONObject> extractedObjects = new LinkedList<>();
        JSONArray gg = (JSONArray) this.data.opt(PEDIGREE_JSON_DATA_KEY);
        // letting it throw a null exception on purpose
        for (Object nodeObj : gg) {
            JSONObject node = (JSONObject) nodeObj;

            JSONObject properties = (JSONObject) node.opt(PROP_JSON_KEY);
            if (properties == null || properties.length() == 0) {
                continue;
            }

            Object id = properties.opt(DefaultPedigree.PATIENT_LINK_JSON_KEY);
            if (id == null || StringUtils.isBlank(id.toString())) {
                continue;
            }

            // check for family_history: consanguinity
            // ignore for children who are adopted IN (still do for adopted OUT)
            String adopted = properties.optString("adoptedStatus");
            if (!"adoptedIn".equals(adopted)) {
                Boolean consang = getConsanguinity(node, gg);
                JSONObject famHistory = (JSONObject) properties.opt(FAMILY_HISTORY_JSON_KEY);
                // either add or modify consanguinity if existing
                if (famHistory != null) {
                    famHistory.put(CONSANGUINITY_JSON_KEY, consang);
                } else {
                    JSONObject familyJson = new JSONObject();
                    familyJson.put(CONSANGUINITY_JSON_KEY, consang);
                    properties.put(FAMILY_HISTORY_JSON_KEY, familyJson);
                }
            }

            extractedObjects.add(properties);
        }
        return extractedObjects;
    }

    @Override
    public String getProbandId()
    {
        return getProbandInfo().getLeft();
    }

    @Override
    public String getProbandPatientLastName()
    {
        String lastName = getProbandInfo().getRight();
        if (StringUtils.isBlank(lastName)) {
            return null;
        }
        return lastName;
    }

    /**
     * @return a pair {@code <ProbandId, ProbandLastname>}
     */
    private Pair<String, String> getProbandInfo()
    {
        int probandNodeId = this.data.optInt(PEDIGREE_JSON_PROBAND_KEY, -1);
        if (probandNodeId == -1) {
            // no proband ID, no proband last name
            return Pair.of(null, null);
        }
        JSONArray gg = (JSONArray) this.data.opt(PEDIGREE_JSON_DATA_KEY);
        for (Object nodeObj : gg) {
            JSONObject node = (JSONObject) nodeObj;
            if (probandNodeId == node.optInt(ID_JSON_KEY, -1)) {
                JSONObject properties = (JSONObject) node.opt(PROP_JSON_KEY);
                if (properties != null) {
                    String id = properties.optString(DefaultPedigree.PATIENT_LINK_JSON_KEY);
                    if (!StringUtils.isBlank(id)) {
                        String lastName = properties.optString(DefaultPedigree.PATIENT_LASTNAME_JSON_KEY);
                        return Pair.of(id, lastName);
                    }
                }
                break;
            }
        }
        return Pair.of(null, null);
    }

    private Boolean getConsanguinity(JSONObject node, JSONArray gg)
    {
        // find producing relationship to be able to detect consanguinity
        int patientNodeId = node.getInt(ID_JSON_KEY);
        // 1) find childHub node which has patientNodeId in the outedges array
        JSONObject childhub = getOutedgesNode(gg, patientNodeId);
        if (childhub == null) {
            return null;
        }
        int childhubID = childhub.getInt(ID_JSON_KEY);
        // 2) find relationship node which has childhubID in the outedges array
        JSONObject relhub = getOutedgesNode(gg, childhubID);
        if (relhub == null) {
            return null;
        }
        // 3) check prop for that, if it has "consangr"
        JSONObject relhubProperties = (JSONObject) relhub.opt(PROP_JSON_KEY);
        if (relhubProperties != null && relhubProperties.length() != 0) {
            String consangr = relhubProperties.optString("consangr");
            if (consangr != null) {
                return ("Y".equals(consangr)) ? true : false;
            }
        }
        return null;
    }

    private JSONObject getOutedgesNode(JSONArray gg, int id)
    {
        for (Object nodeObj : gg) {
            JSONObject node = (JSONObject) nodeObj;
            JSONArray outedges = node.optJSONArray("outedges");
            if (outedges == null || outedges.length() == 0) {
                continue;
            }
            for (Object edge : outedges) {
                JSONObject el = (JSONObject) edge;
                if (el.getInt("to") == id) {
                    return node;
                }
            }
        }
        return null;
    }

    @Override
    public void removeLink(String linkedPatientId)
    {
        // update SVG
        this.image = SvgUpdater.removeLink(this.image, linkedPatientId);

        // update JSON
        removeLinkFromPedigreeJSON(linkedPatientId);
    }

    /*
     * Removes all links to the given PhenoTips patient form the pedigree JSON.
     */
    private void removeLinkFromPedigreeJSON(String linkedPatientId)
    {
        List<JSONObject> patientProperties = this.extractPatientJSONProperties();
        for (JSONObject properties : patientProperties) {
            Object patientLink = properties.opt(DefaultPedigree.PATIENT_LINK_JSON_KEY);
            if (patientLink != null && StringUtils.equalsIgnoreCase(patientLink.toString(), linkedPatientId)) {
                properties.remove(DefaultPedigree.PATIENT_LINK_JSON_KEY);
            }
        }
    }
}
