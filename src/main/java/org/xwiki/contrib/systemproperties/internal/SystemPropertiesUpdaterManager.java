/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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
package org.xwiki.contrib.systemproperties.internal;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseObjectReference;
import com.xpn.xwiki.objects.PropertyInterface;

import org.apache.batik.util.ParsedURL;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.systemproperties.SystemPropertiesUpdaterConfiguration;
import org.xwiki.model.reference.AttachmentReference;
import org.xwiki.model.reference.AttachmentReferenceResolver;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.ObjectPropertyReference;
import org.xwiki.model.reference.ObjectPropertyReferenceResolver;
import org.xwiki.model.reference.ObjectReference;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Properties;

/**
 * Manager for setting system properties.
 *
 * @version $Id$
 * @since 1.0
 */
@Component(roles = SystemPropertiesUpdaterManager.class)
@Singleton
public class SystemPropertiesUpdaterManager
{
    private static final String PROPERTY_PREFIX = "property";

    private static final String ATTACHMENT_PREFIX = "attachment";

    private static final String PREFIX_TEMPLATE = "%s:%s:";

    private static final String DOUBLE_QUOTE = "\"";

    @Inject
    private Logger logger;

    @Inject
    private ObjectPropertyReferenceResolver<String> objectPropertyReferenceResolver;

    @Inject
    private AttachmentReferenceResolver<String> attachmentReferenceResolver;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private SystemPropertiesUpdaterConfiguration systemPropertiesUpdaterConfiguration;

    /**
     * Apply system properties on the given wiki.
     *
     * @param wikiId the ID of the wiki to update
     */
    public void updateProperties(String wikiId)
    {
        Properties properties = System.getProperties();
        if (logger.isDebugEnabled()) {
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                logger.debug("Found system property [{}] with value [{}]", entry.getKey(), entry.getValue());
            }
        }

        XWikiContext context = contextProvider.get();
        XWiki xwiki = context.getWiki();
        String propertyPrefix = String.format(PREFIX_TEMPLATE, PROPERTY_PREFIX, wikiId);
        String attachmentPrefix = String.format(PREFIX_TEMPLATE, ATTACHMENT_PREFIX, wikiId);

        for (Map.Entry<Object, Object> variable : properties.entrySet()) {
            if (variable.getKey() instanceof String) {
                String key = (String) variable.getKey();
                Object value = maybeSanitizeValue(variable.getValue());

                if (StringUtils.startsWith(key, propertyPrefix)) {
                    ObjectPropertyReference reference =
                        objectPropertyReferenceResolver.resolve(StringUtils.removeStart(key, propertyPrefix));
                    updateProperty(reference, value, context, xwiki);
                } else if (StringUtils.startsWith(key, attachmentPrefix)) {
                    AttachmentReference reference =
                        attachmentReferenceResolver.resolve(StringUtils.removeStart(key, attachmentPrefix));
                    updateAttachment(reference, value, context, xwiki);
                }
            }
        }
    }

    private void updateProperty(ObjectPropertyReference reference, Object value, XWikiContext context, XWiki xwiki)
    {
        DocumentReference documentReference = ((ObjectReference) reference.getParent()).getDocumentReference();
        logger.debug("Found object reference [{}] for document [{}]", reference, documentReference);
        try {
            XWikiDocument document = xwiki.getDocument(documentReference, context).clone();
            BaseObject object =
                document.getXObject(new BaseObjectReference(reference.getParent()).getXClassReference(), true, context);

            // We'll need to check if the object actually needs to be updated by the property
            // in order to avoid non-needed history entries
            // For this, we'll use a trick : check if the object PropertyInterface before
            // putting the property is still the same as after.
            PropertyInterface oldPropertyInterface = object.get(reference.getName());
            object.set(reference.getName(), value, context);
            // If the old property interface is null, it means that the object has just been created
            if (oldPropertyInterface == null || !object.get(reference.getName()).equals(oldPropertyInterface)) {
                logger.info("Updating object property [{}] to value [{}] from system properties", reference, value);
                xwiki.saveDocument(document,
                        String.format("Updated property [%s] from system properties",
                                reference.getName()), context);
            }
        } catch (XWikiException e) {
            logger.error("Failed to update property [{}]", reference, e);
        }
    }

    private void updateAttachment(AttachmentReference reference, Object value, XWikiContext context, XWiki xwiki)
    {
        try {
            URI uri = (value instanceof URI) ? (URI) value : new URI((String) value);
            byte[] newFileBytes = getAttachmentBytes(uri, reference, value);

            if (newFileBytes != null) {
                // We want to verify that there's not already existing attachment in XWiki that corresponds exactly
                // to the attahcment we are trying to provide.
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] newAttachmentSum = digest.digest(newFileBytes);


                XWikiDocument document = xwiki.getDocument(reference.getDocumentReference(), context).clone();
                XWikiAttachment attachment = new XWikiAttachment(document, reference.getName());
                InputStream currentAttachmentIS = attachment.getContentInputStream(context);
                byte[] currentAttachmentSum = null;
                if (currentAttachmentIS != null) {
                    byte[] currentAttachmentBytes = IOUtils.toByteArray(currentAttachmentIS);
                    currentAttachmentIS.close();
                    currentAttachmentSum = digest.digest(currentAttachmentBytes);
                }
                if (currentAttachmentSum != newAttachmentSum) {
                    attachment.setContent(new ByteArrayInputStream(newFileBytes));
                    document.setAttachment(attachment);
                    xwiki.saveDocument(document,
                        String.format("Updated attachment [%s] from system properties", reference.getName()), context);
                }
            }
        } catch (URISyntaxException | IOException | NoSuchAlgorithmException | XWikiException e) {
            logger.error("Failed to update attachment [{}] from system property with value [{}]", reference, value, e);
        }
    }

    private byte[] getAttachmentBytes(URI uri, AttachmentReference reference, Object value)
    {
        byte[] bytes = null;

        if (uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https")) {
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpGet httpGet = new HttpGet(uri);
                CloseableHttpResponse response = httpClient.execute(httpGet);
                bytes = IOUtils.toByteArray(response.getEntity().getContent());
            } catch (IOException e) {
                logger.error("Failed to fetch attachment [{}] from URL [{}]", reference, uri, e);
            }
        } else if (uri.getScheme().equalsIgnoreCase("file")) {
            try {
                bytes = IOUtils.toByteArray(Files.newInputStream(Path.of(uri)));
            } catch (IOException e) {
                logger.error("Failed to open file [{}] for attachment [{}]", uri, reference, e);
            }
        } else if (uri.getScheme().equalsIgnoreCase("data")) {
            try {
                ParsedURL parsedURL = new ParsedURL(uri.toString());

                if (parsedURL.complete()) {
                    bytes = IOUtils.toByteArray(parsedURL.openStream());
                }
            } catch (IOException e) {
                logger.error("Failed to parse data URL [{}] for attachment [{}]", uri, reference, e);
            }
        } else {
            logger.error("Unsupported attachment URI [{}] from system property [{}]", value, reference);
        }

        return bytes;
    }

    private Object maybeSanitizeValue(Object value)
    {
        if (value instanceof String && systemPropertiesUpdaterConfiguration.trimDoubleQuotes()
            && ((String) value).startsWith(DOUBLE_QUOTE) && ((String) value).endsWith(DOUBLE_QUOTE)) {
            return ((String) value).substring(1, ((String) value).length() - 1);
        }
        return value;
    }
}
