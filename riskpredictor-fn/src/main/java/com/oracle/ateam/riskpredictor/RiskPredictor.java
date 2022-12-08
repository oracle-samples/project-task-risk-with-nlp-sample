/*******************************************************************************
 * RiskPredictor Function version 1.0.
 *
 * Copyright (c)  2022,  Oracle and/or its affiliates.
 * Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
 ******************************************************************************/
package com.oracle.ateam.riskpredictor;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.owasp.encoder.Encode;

import com.fnproject.fn.api.InputEvent;
import com.fnproject.fn.api.httpgateway.HTTPGatewayContext;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ResourcePrincipalAuthenticationDetailsProvider;
import com.oracle.bmc.http.signing.RequestSigningFilter;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import javax.ws.rs.client.*;
import javax.ws.rs.core.*;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.json.JsonNumber;
import javax.json.JsonString;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.oracle.bmc.secrets.SecretsClient;
import com.oracle.bmc.secrets.model.Base64SecretBundleContentDetails;
import com.oracle.bmc.secrets.requests.GetSecretBundleRequest;
import com.oracle.bmc.secrets.responses.GetSecretBundleResponse;

public class RiskPredictor {
    private final Logger logger = Logger.getLogger("RiskPredictor");
    private final String faSecretId = System.getenv("FA_SECRET_OCID");
    private final String ociRegion = System.getenv().get("OCI_REGION");
    private static final String X_WAGGLE_RANDOME_HEADER = "X-Waggle-RandomID";
    private static final int MIN_MESSAGE_AGE = 24;
    private static final String OSN_PPM_VO = "PJT.oracle.apps.projects.projectManagement.common.publicModel.view.PjtOSNIntegrationTaskVO";
    private String username = System.getenv().get("FA_USER");
    private String projectIds = System.getenv().get("PROJECT_ID_LIST");
    private String faBaseURL = System.getenv().get("FA_BASE_URL");
    private String osnRestAPI = faBaseURL + "/osn/social/api/v1";
    private String ppmRestAPI = faBaseURL + "/fscmRestApi/resources/11.13.18.05";
    private String hcmRestAPI = faBaseURL + "/hcmRestApi/resources/11.13.18.05";
    private String odsRestAPI = System.getenv().get("ODS_MODEL_URL");
    private String xWaggleRandomID = "";
    private CloseableHttpClient httpclient = null;    
    private SecretsClient secretsClient;
    private BasicAuthenticationDetailsProvider provider = null;
   
    private String itemText = "";

    public String handleRequest(HTTPGatewayContext ctx, InputEvent input) {
        logger.log(Level.INFO, "Start prediction...");
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        if (!ctx.getMethod().equalsIgnoreCase("Get")) {
            ctx.setStatusCode(500);
            return "HTTP Method not supported: " + ctx.getMethod();
        }
        if (input != null) {
            ctx.setStatusCode(500);
            return "No data/payload is required ";
        }
        try {
            initSecretClient();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable get Secret: {0}", e.getMessage());
            ctx.setStatusCode(500);
            return "Unable get Secret";
        }

        List<String> projectList = new ArrayList<>(Arrays.asList(projectIds.split(",")));
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(username, getSecretValue(faSecretId)));
        httpclient = HttpClients.custom().setDefaultCredentialsProvider(credsProvider).build();
        for (String projectId : projectList) {
            Project project = getProjectByID(projectId);
            String personId = getProjectManagerPersonId(project.id);
            String uname = getUserName(personId);
            String userOSNWallURL = getPeopleWallURL(uname);
            List<Task> tasks = getProjectTasks(projectId);
            itemText = "";
            String headerText = "The project " + Encode.forHtmlContent(project.name) + " task risk sentiment as of " + new Date() + "<br><ul>";

            for (Task task : tasks) {
                JsonObject so = getSocialObjectId(task.externalId);
                if (so != null) {
                    String taskName = so.getString("name");
                    String soid = so.getString("id");
                    if (soid != null) {
                        List<String> msgList = getMesssages(soid);
                        if (!msgList.isEmpty()) {
                            for (String msg : msgList) {
                                generateItemText(msg, taskName, task.getProgressStatusCode());
                            }
                        }
                    }
                }
            }
            if (itemText.length() > 0) {
                itemText = itemText + "</ul>";
            } else {
                itemText = "<li>No prediction</li></ul>";
            }
            String msg = headerText + itemText;
            postMesssage(msg, userOSNWallURL);
        }
        return "Prediction Completed";
    }

    /**
     * Genearte OSN message project task text with prediction result
     * 
     * @param msg
     * @param taskName
     * @param statusCode
     */
    private void generateItemText(String msg, String taskName, String statusCode) {
        if (!msg.isEmpty()) {
            String predictionTxt = "";
            String predictionColor = "";
            JsonObject output = invokeModel(msg);
            if (output == null) return;
            String predict = output.getString("prediction");
            switch (predict) {
                case "GREEN":
                    predictionTxt = "Green";
                    predictionColor = "Green";
                    break;
                case "AMBER":
                    predictionTxt = "AMBER";
                    predictionColor = "#FFBF00";
                    break;
                case "RED":
                    predictionTxt = "RED";
                    predictionColor = "Red";
                    break;
                default:
                    predictionTxt = "";
            }
            double p = (double) toObject(output.get("probability"));
            NumberFormat format = NumberFormat.getPercentInstance();
            format.setMinimumFractionDigits(2);
            String probability = format.format(p);
            switch (statusCode) {
                case "COMPLETED":
                    itemText = itemText + "<li>The " + Encode.forHtmlContent(taskName) + " has been completed";
                    break;
                case "IN_PROGRESS":
                    itemText = itemText + "<li>The " + Encode.forHtmlContent(taskName)
                            + " task is in progress. This task risk level: <strong style='color: " + Encode.forHtmlAttribute(predictionColor) + "'>"
                            + Encode.forHtml(predictionTxt) + "</strong>, with prediction probability of " + Encode.forHtmlContent(probability) + "</li>";
                    break;
                case "NOT_STARTED":
                    itemText = itemText + "<li>The " + Encode.forHtmlContent(taskName)
                            + " task has not been started. This task risk level: <strong style='color: " + Encode.forHtmlAttribute(predictionColor) + "'>"
                            + Encode.forHtml(predictionTxt) + "</strong>, with prediction probability of " + Encode.forHtmlContent(probability) + "</li>";
                    break;
                default:
                    logger.log(Level.INFO, "No statue code");
            }
        }
    }

    /**
     * Initialize the OCI secret client.
     */
    private void initSecretClient() {
        logger.log(Level.INFO, "Initial OCI client using resource principal");
        String version = System.getenv("OCI_RESOURCE_PRINCIPAL_VERSION");
        if (version != null) {
            provider = ResourcePrincipalAuthenticationDetailsProvider.builder().build();
        } else {
            try {
                provider = new ConfigFileAuthenticationDetailsProvider("~/.oci/config", "DEFAULT");
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Unable initialize secret client: {0}", e.getMessage());
            }
        }
        secretsClient = new SecretsClient(provider);
        secretsClient.setRegion(Region.valueOf(ociRegion));
    }

    /**
     * Get the secret value from the OCI vault.
     * 
     * @param secretOcid The OCI ID of the secret
     * @return the secret in plain text format.
     * @throws IOException
     */
    private String getSecretValue(String secretOcid) {

        // create get secret bundle request
        GetSecretBundleRequest getSecretBundleRequest = GetSecretBundleRequest.builder().secretId(secretOcid)
                .stage(GetSecretBundleRequest.Stage.Current).build();

        // get the secret
        GetSecretBundleResponse getSecretBundleResponse = secretsClient.getSecretBundle(getSecretBundleRequest);

        // get the bundle content details
        Base64SecretBundleContentDetails base64SecretBundleContentDetails = (Base64SecretBundleContentDetails) getSecretBundleResponse
                .getSecretBundle().getSecretBundleContent();

        // decode the encoded secret
        byte[] secretValueDecoded = Base64.decodeBase64(base64SecretBundleContentDetails.getContent());
        return new String(secretValueDecoded);
    }

    /**
     * Convert json value to an object type.
     * 
     * @param jsonValue
     * @return an object
     */
    private Object toObject(JsonValue jsonValue) {
        switch (jsonValue.getValueType()) {
            case ARRAY:
                return jsonValue.toString();
            case OBJECT:
                return jsonValue.toString();
            case STRING:
                return ((JsonString) jsonValue).getString();
            case NUMBER:
                return ((JsonNumber) jsonValue).doubleValue();
            case TRUE:
                return true;
            case FALSE:
                return false;
            case NULL:
                return null;
            default:
                return jsonValue.toString();
        }
    }

    /**
     * First, make a GET call to the REST API end point. This triggers a redirect to
     * OAM, where basic authentication occurs.
     */
    private void getOSNRandaomID() throws IOException {
        HttpGet httpGet = new HttpGet(osnRestAPI);
        HttpResponse response;
        try {
            response = httpclient.execute(httpGet);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                logger.log(Level.SEVERE, "Basic Auth did not work. The status code was: {0}", statusCode);
                httpGet.releaseConnection();
                return;
            }
            httpGet.releaseConnection();
            HttpPost httpPost = new HttpPost(osnRestAPI + "/connections");
            response = httpclient.execute(httpPost);
            try {
                HttpEntity entity = response.getEntity();
                String result = EntityUtils.toString(entity);
                JsonObject json = jsonFromString(result);
                xWaggleRandomID = json.getString("apiRandomID");
            } finally {
                httpPost.releaseConnection();
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Basic Auth did not work. The status code was: {0}", e.getMessage());
        }
    }

    /**
     * Get OSN wall URL using user name
     * 
     * @param uname
     * @return OSN wall URL in string
     */
    private String getPeopleWallURL(String uname) {
        try {
            getOSNRandaomID();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to connect to OSN: {0}", e.getMessage());
            return "Unable to connect to OSN";
        }
        String url = osnRestAPI + "/people/" + uname;
        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader(X_WAGGLE_RANDOME_HEADER, xWaggleRandomID);
        HttpResponse response;
        String peopleWallURL = null;
        try {
            response = httpclient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity, "UTF-8");
            JsonObject json = jsonFromString(result);
            peopleWallURL = json.getString("wallURL");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable get people wall: {0}", e.getMessage());
        } finally {
            httpGet.releaseConnection();
        }
        return peopleWallURL;
    }

    /**
     * Get Username using HCM REST API.
     * 
     * @param persondId
     * @return username as String
     */
    private String getUserName(String persondId) {
        String uname = null;
        HttpGet httpGet = new HttpGet(hcmRestAPI + "/userAccounts");
        HttpResponse response;
        try {
            List<NameValuePair> nameValuePairs = new ArrayList<>();
            nameValuePairs.add(new BasicNameValuePair("q", "PersonId=" + persondId));
            URI uri = new URIBuilder(httpGet.getURI()).addParameters(nameValuePairs).build();
            httpGet.setURI(uri);
            response = httpclient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity, "UTF-8");
            JsonObject json = jsonFromString(result);
            JsonArray ja = json.getJsonArray("items");
            for (int i = 0; i < ja.size(); i++) {
                uname = ja.getJsonObject(i).getString("Username");
            }
        } catch (IOException | URISyntaxException e) {
            logger.log(Level.SEVERE, "Unable get user name: {0}", e.getMessage());
        } finally {
            httpGet.releaseConnection();
        }
        return uname;
    }

    /**
     * Get PPM Project using project ID
     * 
     * @param projctId
     * @return the project id in string
     */
    private Project getProjectByID(String projectId) {
        Project project = new Project();
        HttpGet httpGet = new HttpGet(ppmRestAPI
                + "/projects?fields=ProjectName,ProjectStatus,ProjectNumber,ProjectId&onlyData=True&limit=28433095&q=ProjectNumber="
                + projectId);
        try {
            HttpResponse response = httpclient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity, "UTF-8");
            JsonObject json = jsonFromString(result);
            JsonArray ja = json.getJsonArray("items");
            for (int i = 0; i < ja.size(); i++) {
                project.name = ja.getJsonObject(i).get("ProjectName").toString();
                project.id = ja.getJsonObject(i).get("ProjectId").toString();
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable get project by id: {0}", e.getMessage());
        } finally {
            httpGet.releaseConnection();
        }
        return project;
    }

    /**
     * Get Project Manager Person Id
     * 
     * @param projctId
     * @return
     */
    private String getProjectManagerPersonId(String projectId) {
        String personId = "";
        HttpGet httpGet = new HttpGet(ppmRestAPI + "/projects/" + projectId + "/child/ProjectTeamMembers");
        try {
            List<NameValuePair> nameValuePairs = new ArrayList<>();
            nameValuePairs.add(new BasicNameValuePair("q", "ProjectRole='Project Manager'"));
            URI uri = new URIBuilder(httpGet.getURI()).addParameters(nameValuePairs).build();
            httpGet.setURI(uri);
            HttpResponse response = httpclient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity, "UTF-8");

            JsonObject json = jsonFromString(result);
            JsonArray ja = json.getJsonArray("items");
            for (int i = 0; i < ja.size(); i++) {
                personId = ja.getJsonObject(i).get("PersonId").toString();
            }
        } catch (IOException | URISyntaxException e) {
            logger.log(Level.SEVERE, "Unable get project manager by project ID: {0} ", e.getMessage());
        } finally {
            httpGet.releaseConnection();
        }
        return personId;
    }

    /**
     * Get a list of task external id from the project plans
     * 
     * @param projctId
     * @return a list of task external id
     */
    private List<Task> getProjectTasks(String projectId) {
        String taskId = null;
        String planLineId = null;
        String externalId = null;
        String progressStatusCode = null;
        List<Task> externdIdList = new ArrayList<>();
        HttpGet httpGet = new HttpGet(ppmRestAPI + "/projectPlans/" + projectId
                + "/child/Tasks?fields=Name,TaskId,PlanLineId&onlyData=true");
        try {
            HttpResponse response = httpclient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity, "UTF-8");
            JsonObject json = jsonFromString(result);
            JsonArray ja = json.getJsonArray("items");
            for (int i = 0; i < ja.size(); i++) {
                Task task = new Task();
                taskId = ja.getJsonObject(i).get("TaskId").toString();
                planLineId = ja.getJsonObject(i).get("PlanLineId").toString();
                progressStatusCode = getTaskProgress(taskId, projectId);
                externalId = OSN_PPM_VO + "." + planLineId + "_" + taskId;
                task.setExternalId(externalId);
                task.setProjectId(projectId);
                task.setProgressStatusCode(progressStatusCode);
                externdIdList.add(task);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable get project task external id by project ID: {0} ", e.getMessage());
        } finally {
            httpGet.releaseConnection();
        }
        return externdIdList;
    }

    private String getTaskProgress(String taskId, String projectId) {
        String progressStatusCode = null;
        HttpGet httpGet = new HttpGet(ppmRestAPI + "/projects/" + projectId
                + "/child/Tasks?fields=ProgressStatusCode&onlyData=true&limit=28433095&q=TaskId=" + taskId);
        try {
            HttpResponse response = httpclient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity, "UTF-8");
            JsonObject json = jsonFromString(result);
            JsonArray ja = json.getJsonArray("items");
            for (int i = 0; i < ja.size(); i++) {
                progressStatusCode = ja.getJsonObject(i).getString("ProgressStatusCode");
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable get project task external id by project ID: {0} ", e.getMessage());
        } finally {
            httpGet.releaseConnection();
        }
        return progressStatusCode;
    }

    /**
     * Get social object for a task external id
     * 
     * @param externalId
     * @return json object of the social object
     */
    private JsonObject getSocialObjectId(String externalId) {
        try {
            getOSNRandaomID();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable get social object id by external id:  {0} ", e.getMessage());
            return null;
        }
        JsonObject json = null;
        HttpGet httpGet = new HttpGet(osnRestAPI + "/socialObjects/" + externalId);
        httpGet.setHeader(X_WAGGLE_RANDOME_HEADER, xWaggleRandomID);
        try {
            HttpResponse response = httpclient.execute(httpGet);
            if (response.getStatusLine().getStatusCode() != 200) {
                return null;
            }
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity, "UTF-8");
            json = jsonFromString(result);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable get social object id by external id:  {0} ", e.getMessage());
        } finally {
            httpGet.releaseConnection();
        }
        return json;
    }

    /**
     * Post a message to user wall in OSN
     * 
     * @param msg
     * @param userWallURL
     */
    private void postMesssage(String msg, String userWallURL) {
        try {
            getOSNRandaomID();
            HttpPost httpPost = new HttpPost(userWallURL + "/messages");
            JsonObject data = Json.createObjectBuilder().add("message", msg).build();
            String json = data.toString();
            StringEntity entity = new StringEntity(json);
            entity.setContentType("application/json");
            httpPost.setEntity(entity);
            httpPost.setHeader("Accept", "application/json");
            httpPost.setHeader("Content-type", "application/json");
            httpPost.setHeader(X_WAGGLE_RANDOME_HEADER, xWaggleRandomID);
            HttpResponse response = httpclient.execute(httpPost);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                logger.log(Level.SEVERE, "Unable to post message to OSN user wall: {0}", statusCode);
                httpPost.releaseConnection();
            } else {
                logger.log(Level.INFO, "Posted message to OSN user wall, status code: {0}", statusCode);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to post message to OSN user wall: {0}", e.getMessage());
        }
    }

    /**
     * Get social object messages using the social object id
     * 
     * @param objectId
     * @return a list of text messages
     */
    private List<String> getMesssages(String objectId) {
        List<String> msgList = new ArrayList<>();
        String url = osnRestAPI + "/socialObjects/" + objectId + "/messages/";
        HttpGet httpGet = new HttpGet(url);
        try {
            getOSNRandaomID();
            HttpResponse response = httpclient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity, "UTF-8");
            JsonObject json = jsonFromString(result);
            JsonArray ja = json.getJsonArray("items");
            long epochDate = ja.getJsonObject(0).getJsonNumber("createdDate").longValue();
            LocalDateTime createdDate = Instant.ofEpochMilli(epochDate).atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
            Duration duration = Duration.between(LocalDateTime.now(), createdDate);
            long diff = Math.abs(duration.toHours());
            if (diff > MIN_MESSAGE_AGE) {
                logger.log(Level.WARNING, "The message is {0} hours old", diff);
            } else {
                msgList.add(ja.getJsonObject(0).getString("plainText"));
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to get message by object id: {0}", e.getMessage());
        } finally {
            httpGet.releaseConnection();
        }
        return msgList;
    }

    /**
     * Invoke the ODS model. Pre-Requirement: Allow setting of restricted headers.
     * This is required to allow the SigningFilter to set the host header that gets
     * computed during signing of the request.
     * 
     * @param text
     * @return json object of the model
     */
    private JsonObject invokeModel(String text) {
        JsonObject output = null;
        // 1) Create a request signing filter instance
        RequestSigningFilter requestSigningFilter;
        try {
            if (provider == null) {
                provider = ResourcePrincipalAuthenticationDetailsProvider.builder().build();
            }
            requestSigningFilter = RequestSigningFilter.fromAuthProvider(provider);

            // 2) Create a Jersey client and register the request signing filter
            Client client = ClientBuilder.newBuilder().build().register(requestSigningFilter);
            // 3) Target an endpoint. You must ensure that path arguments and query params
            // are escaped correctly yourself
            WebTarget target = client.target(odsRestAPI).path("predict");

            // 4) Set the expected type and invoke the call
            Invocation.Builder ib = target.request();
            ib.accept(MediaType.APPLICATION_JSON);
            JsonObject data = Json.createObjectBuilder().add("text", text).build();
            Response response = ib.buildPost(Entity.entity(data.toString(), MediaType.APPLICATION_JSON)).invoke();

            // 5) Print the response headers and the body (JSON) as a string
            int status = response.getStatus();
            if (status != 200) {
                logger.log(Level.SEVERE, "Unable to invoke the ODS model: {0}", status);
                return null;
            }
            InputStream responseBody = (InputStream) response.getEntity();
            try (final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(responseBody, StandardCharsets.UTF_8))) {
                StringBuilder jsonBody = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonBody.append(line);
                }
                output = jsonFromString(jsonBody.toString());
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to invoke the ODS model: {0}", e.getMessage());
        }
        return output;
    }

    /**
     * Convert json string to json object.
     * 
     * @param jsonObjectStr
     * @return json object
     */
    private JsonObject jsonFromString(String jsonObjectStr) {
        JsonReader jsonReader = Json.createReader(new StringReader(jsonObjectStr));
        JsonObject object = jsonReader.readObject();
        jsonReader.close();
        return object;
    }

    class Project {
        private String id;
        private String name;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    class Task {
        private String projectId;
        private String progressStatusCode;
        private String externalId;

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getProgressStatusCode() {
            return progressStatusCode;
        }

        public void setProgressStatusCode(String progressStatusCode) {
            this.progressStatusCode = progressStatusCode;
        }

        public String getExternalId() {
            return externalId;
        }

        public void setExternalId(String externalId) {
            this.externalId = externalId;
        }
    }
}