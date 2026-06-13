package io.jrdi.storage.repo;

import io.jrdi.core.symbol.Fqn;

import java.util.List;
import java.util.Optional;

public interface ClassRepo extends Repo {

    record Record(long id, Fqn fqn, int access, Fqn superFqn, Long fileId, String signatureRaw,
                  String source, List<Fqn> interfaces) {}

    long upsert(Fqn fqn, int access, Fqn superFqn, Long fileId, String signatureRaw, String source);

    /**
     * Upsert with the class's implemented interfaces. Used by the bytecode pass
     * to populate {@code classes.interfaces} from ASM's {@code ClassReader.visit()}.
     * The list of interfaces powers the Spring DI candidate resolution's
     * "implements / extends" matching for @Autowired sites.
     */
    long upsert(Fqn fqn, int access, Fqn superFqn, Long fileId, String signatureRaw, String source,
                List<Fqn> interfaces);

    Optional<Record> findByFqn(Fqn fqn);

    void setFileId(long classId, long fileId);

    /**
     * Delete the class row and CASCADE-drop its methods / fields / invokes / lambdas
     * / framework records. Returns the number of class rows deleted (0 or 1).
     */
    int deleteByFqn(Fqn fqn);

    /**
     * Find all indexed classes whose {@code interfaces} list contains {@code iface}
     * (or whose {@code superFqn} equals {@code iface} — covers abstract-class
     * injects). Returns the FQNs of matching classes.
     */
    List<Fqn> findSubtypesOf(Fqn iface);
}
