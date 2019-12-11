/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.cellery.observability.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.cellery.observability.auth.exception.AuthProviderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.wso2.carbon.config.ConfigurationException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manager for managing Open ID Connect related functionality.
 */
public class AuthenticationProvider {
    private static final Logger logger = Logger.getLogger(AuthenticationProvider.class);

    private static final Gson gson = new Gson();
    private static final JsonParser jsonParser = new JsonParser();
    private static final String ERROR = "error";
    private static final String ACTIVE_STATUS = "active";
    private static final String BASIC_AUTH = "Basic ";

    private String clientId;
    private char[] clientSecret;

    public AuthenticationProvider() {
        try {
            retrieveClientCredentials();
        } catch (AuthProviderException e) {
            logger.warn("Fetching Client Credentials failed due to IDP unavailability, " +
                    "will be re-attempted when a user logs in");
        }
    }

    /**
     * Get the client ID of the SP created for observability portal.
     *
     * @return Client Id
     * @throws AuthProviderException if fetching client id failed
     */
    public String getClientId() throws AuthProviderException {
        if (this.clientId == null) {
            synchronized (this) {
                retrieveClientCredentials();
            }
        }
        return this.clientId;
    }

    /**
     * Get the client secret of the SP created for observability portal.
     *
     * @return Client secret
     * @throws AuthProviderException if fetching client secret failed
     */
    public String getClientSecret() throws AuthProviderException {
        if (this.clientSecret == null) {
            synchronized (this) {
                retrieveClientCredentials();
            }
        }
        return String.valueOf(this.clientSecret);
    }

    /**
     * This method will fetch or create and fetch (if client application is not registered)
     * the client application's credentials from IDP.
     *
     * @throws AuthProviderException if getting the Client Credentials fails
     */
    private void retrieveClientCredentials() throws AuthProviderException {
        if (this.clientId == null || this.clientSecret == null) {
            JsonObject jsonObject = createClientWithDcr();
            if (jsonObject.has(ERROR)) {
                logger.info("Fetching the credentials of the already existing client "
                        + Constants.CELLERY_APPLICATION_NAME);
                jsonObject = retrieveExistingClientCredentials();
            }

            this.clientId = jsonObject.get(Constants.OIDC_CLIENT_ID_KEY).getAsString();
            this.clientSecret = jsonObject.get(Constants.OIDC_CLIENT_SECRET_KEY).getAsString().toCharArray();
        }
    }

    /**
     * Register a Client for the Observability Portal.
     *
     * @return the HttpPost object
     * @throws AuthProviderException if the HTTP call for creating the Client fails
     */
    private JsonObject createClientWithDcr() throws AuthProviderException {
        try {
            JsonArray callbackUris = new JsonArray(1);
            callbackUris.add(AuthConfig.getInstance().getCallbackUrl());

            JsonArray grants = new JsonArray(1);
            grants.add(Constants.OIDC_AUTHORIZATION_CODE_KEY);

            String dcrEp = AuthConfig.getInstance().getIdpUrl() + Constants.OIDC_REGISTER_ENDPOINT;
            JsonObject clientJson = new JsonObject();
            clientJson.addProperty(Constants.OIDC_EXT_PARAM_CLIENT_ID_KEY, Constants.CELLERY_CLIENT_ID);
            clientJson.addProperty(Constants.OIDC_CLIENT_NAME_KEY, Constants.CELLERY_APPLICATION_NAME);
            clientJson.add(Constants.OIDC_CALLBACK_URL_KEY, callbackUris);
            clientJson.add(Constants.OIDC_GRANT_TYPES_KEY, grants);

            StringEntity requestEntity = new StringEntity(gson.toJson(clientJson), ContentType.APPLICATION_JSON);

            HttpPost request = new HttpPost(dcrEp);
            request.setHeader(Constants.HEADER_AUTHORIZATION, getEncodedIdpAdminCredentials());
            request.setHeader(Constants.HEADER_CONTENT_TYPE, Constants.CONTENT_TYPE_APPLICATION_JSON);
            request.setEntity(requestEntity);

            HttpClient client = Utils.getTrustAllClient();
            if (logger.isDebugEnabled()) {
                logger.debug("Creating new Client " + Constants.CELLERY_APPLICATION_NAME);
            }
            HttpResponse response = client.execute(request);
            return jsonParser.parse(EntityUtils.toString(response.getEntity())).getAsJsonObject();
        } catch (IOException | ParseException | NoSuchAlgorithmException | KeyManagementException |
                ConfigurationException e) {
            throw new AuthProviderException("Error occurred while registering client", e);
        }
    }

    /**
     * Retrieve the existing credentials for the Observability Portal Client.
     *
     * @return the credentials returned in JSON format
     * @throws AuthProviderException if retrieving existing client credentials fails
     */
    private JsonObject retrieveExistingClientCredentials() throws AuthProviderException {
        try {
            String dcrEp = AuthConfig.getInstance().getIdpUrl() + Constants.OIDC_REGISTER_ENDPOINT;
            HttpGet request = new HttpGet(dcrEp + "?"
                    + Constants.OIDC_CLIENT_NAME_KEY + "=" + Constants.CELLERY_APPLICATION_NAME);
            request.setHeader(Constants.HEADER_AUTHORIZATION, getEncodedIdpAdminCredentials());

            HttpClient client = Utils.getTrustAllClient();
            HttpResponse response = client.execute(request);
            String result = EntityUtils.toString(response.getEntity());

            if (response.getStatusLine().getStatusCode() == 200 && result.contains(Constants.OIDC_CLIENT_ID_KEY)) {
                return jsonParser.parse(result).getAsJsonObject();
            } else {
                throw new AuthProviderException("Error while retrieving client credentials." +
                        " Expected client credentials are not found in the response");
            }
        } catch (IOException | ParseException | NoSuchAlgorithmException | KeyManagementException |
                ConfigurationException e) {
            throw new AuthProviderException("Error occurred while retrieving the client credentials with name " +
                    Constants.CELLERY_APPLICATION_NAME, e);
        }
    }

    /**
     * Validate the token.
     *
     * @param token This will be the access token
     * @return the boolean status of the validity of the access token
     * @throws AuthProviderException if validating the token fails
     */
    public boolean validateToken(String token) throws AuthProviderException {
        try {
            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("token", token));

            String introspectEP = AuthConfig.getInstance().getIdpUrl() + Constants.OIDC_INTROSPECT_ENDPOINT;
            HttpPost request = new HttpPost(introspectEP);
            request.setHeader(Constants.HEADER_AUTHORIZATION, getEncodedIdpAdminCredentials());
            request.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8.name()));

            HttpClient client = Utils.getTrustAllClient();
            HttpResponse response = client.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode >= 200 && statusCode < 400) {
                JsonObject jsonObject = jsonParser.parse(EntityUtils.toString(response.getEntity())).getAsJsonObject();
                if (!jsonObject.get(ACTIVE_STATUS).getAsBoolean()) {
                    return false;
                }
            } else {
                logger.error("Failed to connect to Introspect endpoint in Identity Provider server." +
                        " Exited with Status Code " + statusCode);
                return false;
            }
        } catch (IOException | ParseException | NoSuchAlgorithmException | KeyManagementException |
                ConfigurationException e) {
            throw new AuthProviderException("Error occurred while calling the introspect endpoint", e);
        }
        return true;
    }

    /**
     * Get the Base64 encoded IdP Admin Credentials.
     *
     * @return the String value of encoded credential is returned
     */
    private String getEncodedIdpAdminCredentials() throws ConfigurationException {
        String authString = AuthConfig.getInstance().getIdpUsername() + ":"
                + AuthConfig.getInstance().getIdpPassword();
        byte[] authEncBytes = Base64.encodeBase64(authString.getBytes(Charset.forName(StandardCharsets.UTF_8.name())));
        String authStringEnc = new String(authEncBytes, Charset.forName(StandardCharsets.UTF_8.name()));
        return BASIC_AUTH + authStringEnc;
    }
}
