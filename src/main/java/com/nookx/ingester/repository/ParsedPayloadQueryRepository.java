package com.nookx.ingester.repository;

import com.nookx.ingester.domain.ParsedPayload;
import com.nookx.ingester.domain.enumeration.PushStatus;
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
public class ParsedPayloadQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public List<ParsedPayload> search(
        final String ingestTargetCode,
        final PushStatus pushStatus,
        final String externalIdContains,
        final int limit
    ) {
        final CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        final CriteriaQuery<ParsedPayload> query = cb.createQuery(ParsedPayload.class);
        final Root<ParsedPayload> root = query.from(ParsedPayload.class);
        final List<Predicate> predicates = new ArrayList<>();
        if (ingestTargetCode != null && !ingestTargetCode.isBlank()) {
            predicates.add(cb.equal(root.get("ingestTargetCode"), ingestTargetCode));
        }
        if (pushStatus != null) {
            predicates.add(cb.equal(root.get("pushStatus"), pushStatus));
        }
        if (externalIdContains != null && !externalIdContains.isBlank()) {
            predicates.add(cb.like(root.get("externalId"), "%" + externalIdContains + "%"));
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(cb.desc(root.get("updatedAt")));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList();
    }
}
