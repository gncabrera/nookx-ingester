package com.nookx.ingester.repository;

import com.nookx.ingester.domain.ScrapePage;
import com.nookx.ingester.domain.enumeration.FetchStatus;
import com.nookx.ingester.domain.enumeration.ParseStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ScrapePageQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public List<ScrapePage> search(
        final String sourceCode,
        final String pageType,
        final FetchStatus fetchStatus,
        final ParseStatus parseStatus,
        final String urlContains,
        final int limit
    ) {
        final CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        final CriteriaQuery<ScrapePage> query = cb.createQuery(ScrapePage.class);
        final Root<ScrapePage> root = query.from(ScrapePage.class);
        final List<Predicate> predicates = new ArrayList<>();
        if (sourceCode != null && !sourceCode.isBlank()) {
            predicates.add(cb.equal(root.get("sourceCode"), sourceCode));
        }
        if (pageType != null && !pageType.isBlank()) {
            predicates.add(cb.equal(root.get("pageType"), pageType));
        }
        if (fetchStatus != null) {
            predicates.add(cb.equal(root.get("fetchStatus"), fetchStatus));
        }
        if (parseStatus != null) {
            predicates.add(cb.equal(root.get("parseStatus"), parseStatus));
        }
        if (urlContains != null && !urlContains.isBlank()) {
            predicates.add(cb.like(root.get("url"), "%" + urlContains + "%"));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(cb.desc(root.get("updatedAt")));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }
}
