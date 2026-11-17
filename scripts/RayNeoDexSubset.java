import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Retains the supplied artifact's SDK classes for local interoperability testing. */
public final class RayNeoDexSubset {
    public static void main(String[] args) throws Exception {
        MultiDexContainer<? extends DexFile> container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        List<ClassDef> classes = new ArrayList<>();
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef definition : container.getEntry(entry).getDexFile().getClasses()) {
                if (definition.getType().startsWith("Lcom/ffalcon/xr/")) classes.add(definition);
            }
        }
        if (classes.isEmpty()) throw new IllegalArgumentException("RayNeo SDK classes absent");
        DexPool.writeTo(args[1], new ImmutableDexFile(Opcodes.getDefault(), classes));
        System.out.println("Retained SDK classes " + classes.size());
    }
}
