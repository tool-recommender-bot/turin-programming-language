package me.tomassetti.turin.parser.ast.typeusage;

import me.tomassetti.turin.jvm.JvmType;
import me.tomassetti.turin.parser.analysis.resolvers.SymbolResolver;
import me.tomassetti.turin.parser.ast.Node;

import java.util.List;

public class TypeVariableTypeUsage extends TypeUsage {

    public static class GenericDeclaration {

        private String className;
        private String methodSignature;
        private String constructorSignature;

        public boolean isDeclaredOnClass() {
            return methodSignature == null && constructorSignature == null;
        }

        public boolean isDeclaredOnMethod() {
            return methodSignature != null;
        }

        public boolean isDeclaredOnConstructor() {
            return constructorSignature != null;
        }

        private GenericDeclaration(String className, String constructorSignature, String methodSignature) {
            this.className = className;
            this.constructorSignature = constructorSignature;
            this.methodSignature = methodSignature;
        }

        public static GenericDeclaration onClass(String className) {
            return new GenericDeclaration(className, null, null);
        }

        public static GenericDeclaration onMethod(String className, String methodSignature) {
            return new GenericDeclaration(className, methodSignature, null);
        }

        public static GenericDeclaration onConstructor(String className, String constructorSignature) {
            return new GenericDeclaration(className, null, constructorSignature);
        }
    }

    private String name;
    private List<TypeUsage> bounds;
    private GenericDeclaration genericDeclaration;

    public TypeVariableTypeUsage(GenericDeclaration genericDeclaration, String name, List<TypeUsage> bounds) {
        this.name = name;
        this.genericDeclaration = genericDeclaration;
        this.bounds = bounds;
    }

    @Override
    public JvmType jvmType(SymbolResolver resolver) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<Node> getChildren() {
        throw new UnsupportedOperationException();
    }
}