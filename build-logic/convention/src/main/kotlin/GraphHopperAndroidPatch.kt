import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Two one-line patches that make GraphHopper 11's storage layer run on Android (applied to library classes at
 * dex time; JVM unit tests see the unpatched jar, where both originals work):
 *
 * 1. `RAMDataAccess.<clinit>` builds its `VarHandle`s with `withInvokeExactBehavior()`, a JDK 16 API that Android's
 *    `java.lang.invoke.VarHandle` lacks → `NoSuchMethodError` on first use. The call only tightens invocation
 *    semantics; deleting it leaves the VarHandle itself on the stack, so the field assignment is unchanged.
 * 2. `MMapDataAccess.cleanMappedByteBuffer` unmaps buffers through `sun.misc.Unsafe.invokeCleaner` /
 *    `DirectByteBuffer.cleaner()` reflection, which Android's hidden-API policy blocks → "Unable to unmap the mapped
 *    buffer". Replaced with an empty body: Android releases mapped buffers when they are garbage collected.
 */
abstract class GraphHopperAndroidPatch : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        val className = classContext.currentClassData.className
        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (className == "com.graphhopper.storage.MMapDataAccess" && name == "cleanMappedByteBuffer") {
                    return EmptyVoidBody(mv)
                }
                return object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitMethodInsn(opcode: Int, owner: String, mname: String, desc: String, isInterface: Boolean) {
                        if (owner == "java/lang/invoke/VarHandle" && mname == "withInvokeExactBehavior") return // receiver stays on the stack
                        super.visitMethodInsn(opcode, owner, mname, desc, isInterface)
                    }
                }
            }
        }
    }

    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == "com.graphhopper.storage.RAMDataAccess" || classData.className == "com.graphhopper.storage.MMapDataAccess"

    /** Drops the original instructions and emits `return` for a `static void m(...)`. */
    private class EmptyVoidBody(private val out: MethodVisitor) : MethodVisitor(Opcodes.ASM9, null) {
        override fun visitCode() {
            out.visitCode()
            out.visitInsn(Opcodes.RETURN)
            out.visitMaxs(0, 1)
            out.visitEnd()
        }
    }
}
