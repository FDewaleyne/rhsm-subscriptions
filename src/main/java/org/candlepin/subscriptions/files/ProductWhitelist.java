/*
 * Copyright (c) 2009 - 2019 Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.files;

import org.candlepin.subscriptions.ApplicationProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.PostConstruct;

/**
 * List of products to be considered for capacity calculations.
 */
@Component
public class ProductWhitelist implements ResourceLoaderAware {

    private static Logger log = LoggerFactory.getLogger(ProductWhitelist.class);

    private final Set<String> whitelistProducts = new HashSet<>();
    private final PerLineFileSource source;

    public ProductWhitelist(ApplicationProperties properties) {
        if (properties.getProductWhitelistResourceLocation() != null) {
            source = new PerLineFileSource(
                properties.getProductWhitelistResourceLocation());
        }
        else {
            source = null;
        }
    }

    public boolean productIdMatches(String productId) {
        if (source == null) {
            return true;
        }
        boolean whitelisted = whitelistProducts.contains(productId);
        if (!whitelisted && log.isDebugEnabled()) {
            log.debug("Product ID {} not in whitelist", productId);
        }
        return whitelisted;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        if (source != null) {
            source.setResourceLoader(resourceLoader);
        }
    }

    @PostConstruct
    public void init() throws IOException {
        if (source != null) {
            source.init();
            whitelistProducts.addAll(source.list());
        }
    }
}
