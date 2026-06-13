package io.jrdi.storage.repo;

import java.util.List;

public interface SpringInjectRepo extends Repo {

    enum By { TYPE, NAME, QUALIFIER, VALUE }

    enum Confidence { CERTAIN, PROBABLE, UNCERTAIN }

    record Record(long id, String targetField, Integer targetParamIndex,
                 long classId, Long methodId, String qualifier, By by,
                 Confidence confidence, List<Long> candidateBeanIds) {}

    void record(String targetField, Integer targetParamIndex, long classId, Long methodId,
                String qualifier, By by, Confidence confidence, List<Long> candidateBeanIds);

    List<Record> findByClass(long classId);
}
