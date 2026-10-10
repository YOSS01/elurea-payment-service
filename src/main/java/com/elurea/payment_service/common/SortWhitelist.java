package com.elurea.payment_service.common;

import com.elurea.payment_service.exception.BadRequestException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;
import java.util.TreeSet;

/**
 * Rejects ?sort=... values outside an explicit list of properties, so clients cannot sort on arbitrary
 * (possibly unindexed or nested) entity paths and custom queries never fail with a 500.
 */
public final class SortWhitelist {

    private SortWhitelist() {
    }

    public static Pageable validate(Pageable pageable, Set<String> allowed) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '%s'. Allowed: %s"
                        .formatted(order.getProperty(), String.join(", ", new TreeSet<>(allowed))));
            }
        }
        return pageable;
    }
}
