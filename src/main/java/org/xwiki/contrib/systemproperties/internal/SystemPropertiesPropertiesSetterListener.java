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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.bridge.event.WikiReadyEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.extension.ExtensionId;
import org.xwiki.extension.InstalledExtension;
import org.xwiki.extension.event.ExtensionInstalledEvent;
import org.xwiki.extension.event.ExtensionUpgradedEvent;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.platform.flavor.FlavorManager;
import org.xwiki.text.StringUtils;

/**
 * Event listener that will automatically update object properties upon wiki startup based on the JVM system properties.
 *
 * @version $Id$
 * @since 1.0
 */
@Component
@Singleton
@Named(SystemPropertiesPropertiesSetterListener.LISTENER_NAME)
public class SystemPropertiesPropertiesSetterListener extends AbstractEventListener
{
    /**
     * The listener name.
     */
    public static final String LISTENER_NAME = "SystemPropertiesPropertiesSetterListener";

    @Inject
    private SystemPropertiesUpdaterManager systemPropertiesUpdaterManager;

    @Inject
    private FlavorManager flavorManager;

    @Inject
    private Logger logger;

    /**
     * Builds a new {@link SystemPropertiesPropertiesSetterListener}.
     */
    public SystemPropertiesPropertiesSetterListener()
    {
        super(LISTENER_NAME, new ApplicationReadyEvent(), new WikiReadyEvent(),
                new ExtensionInstalledEvent(), new ExtensionUpgradedEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        if (event instanceof ApplicationReadyEvent) {
            logger.info("Applying system properties on main wiki.");
            systemPropertiesUpdaterManager.updateProperties("xwiki");
        } else if (event instanceof WikiReadyEvent) {
            logger.info("Applying system properties on wiki [{}]", data);
            systemPropertiesUpdaterManager.updateProperties((String) data);
        } else if (event instanceof ExtensionInstalledEvent || event instanceof ExtensionUpgradedEvent) {
            InstalledExtension installedExtension = (InstalledExtension) source;

            if (installedExtension.getNamespaces() != null && "flavor".equals(installedExtension.getCategory())) {
                for (String namespace : installedExtension.getNamespaces()) {
                    String wikiId = StringUtils.removeStart(namespace, "wiki:");
                    ExtensionId wikiFlavor = flavorManager.getFlavorOfWiki(wikiId);
                    if (installedExtension.getId().equals(wikiFlavor)) {
                        logger.info(
                            "Applying system properties on wiki [{}] following installation or upgrade of flavor [{}]",
                            wikiId, wikiFlavor);
                        systemPropertiesUpdaterManager.updateProperties(wikiId);
                    }
                }
            }
        }
    }
}
