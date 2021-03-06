package me.tomassetti.turin.parser.ast;

import com.google.common.collect.ImmutableList;
import me.tomassetti.turin.compiler.ParamUtils;
import me.tomassetti.turin.compiler.errorhandling.ErrorCollector;
import me.tomassetti.jvm.JvmNameUtils;
import me.tomassetti.turin.definitions.TypeDefinition;
import me.tomassetti.turin.parser.analysis.exceptions.UnsolvedConstructorException;
import me.tomassetti.jvm.JvmConstructorDefinition;
import me.tomassetti.jvm.JvmMethodDefinition;
import me.tomassetti.jvm.JvmType;
import me.tomassetti.turin.parser.analysis.*;
import me.tomassetti.turin.resolvers.SymbolResolver;
import me.tomassetti.turin.resolvers.jdk.ReflectionTypeDefinitionFactory;
import me.tomassetti.turin.definitions.InternalConstructorDefinition;
import me.tomassetti.turin.definitions.InternalMethodDefinition;
import me.tomassetti.turin.parser.ast.annotations.AnnotationUsage;
import me.tomassetti.turin.parser.ast.expressions.ActualParam;
import me.tomassetti.turin.parser.ast.invokables.TurinTypeContructorDefinitionNode;
import me.tomassetti.turin.parser.ast.invokables.TurinTypeMethodDefinitionNode;
import me.tomassetti.turin.parser.ast.properties.PropertyDefinition;
import me.tomassetti.turin.parser.ast.properties.PropertyReference;
import me.tomassetti.turin.parser.ast.typeusage.TypeUsageNode;
import me.tomassetti.turin.parser.ast.typeusage.VoidTypeUsageNode;
import me.tomassetti.turin.symbols.FormalParameter;
import me.tomassetti.turin.symbols.Symbol;
import me.tomassetti.turin.typesystem.Invokable;
import me.tomassetti.turin.typesystem.ReferenceTypeUsage;
import me.tomassetti.turin.typesystem.TypeUsage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Type defined in Turin.
 */
public class TurinTypeDefinition extends TypeDefinitionNode {
    private List<Node> members = new ArrayList<>();
    private List<TypeUsageNode> interfaces = new ArrayList<>();
    private Optional<TypeUsageNode> baseType = Optional.empty();

    private List<AnnotationUsage> annotations = new ArrayList<>();

    public List<TurinTypeContructorDefinitionNode> getExplicitConstructors() {
        return members.stream()
                .filter((m) -> m instanceof TurinTypeContructorDefinitionNode)
                .map((m) -> (TurinTypeContructorDefinitionNode) m)
                .collect(Collectors.toList());
    }

    @Override
    protected boolean specificValidate(SymbolResolver resolver, ErrorCollector errorCollector) {
        if (baseType.isPresent()) {
            if (!baseType.get().isReferenceTypeUsage() || !baseType.get().asReferenceTypeUsage().isClass(resolver)) {
                errorCollector.recordSemanticError(baseType.get().getPosition(), "Only classes can be extended");
                return false;
            }
        }

        for (TypeUsageNode typeUsage : interfaces) {
            if (!typeUsage.isReferenceTypeUsage() || !typeUsage.asReferenceTypeUsage().isInterface(resolver)) {
                errorCollector.recordSemanticError(typeUsage.getPosition(), "Only interfaces can be implemented");
                return false;
            }
        }

        if (getExplicitConstructors().size() > 1) {
            for (TurinTypeContructorDefinitionNode contructorDefinition : getExplicitConstructors()) {
                errorCollector.recordSemanticError(contructorDefinition.getPosition(), "At most one explicit constructor can be defined");
            }
            return false;
        }

        return super.specificValidate(resolver, errorCollector);
    }

    public void setBaseType(TypeUsageNode baseType) {
        baseType.setParent(this);
        this.baseType = Optional.of(baseType);
    }

    public void addInterface(TypeUsageNode interfaze) {
        interfaze.setParent(this);
        interfaces.add(interfaze);
    }

    public void addAnnotation(AnnotationUsage annotation) {
        annotation.setParent(this);
        annotations.add(annotation);
    }

    public String getQualifiedName() {
        String contextName = contextName();
        if (contextName.isEmpty()) {
            return name;
        } else {
            return contextName + "." + name;
        }
    }

    private Map<String, List<InternalMethodDefinition>> methodsByName;
    private List<InternalConstructorDefinition> constructors;

    private void registerMethod(InternalMethodDefinition method) {
        if (!methodsByName.containsKey(method.getMethodName())){
            methodsByName.put(method.getMethodName(), new ArrayList<>());
        }
        methodsByName.get(method.getMethodName()).add(method);
    }

    private void initializeMethodsByName(SymbolResolver resolver) {
        methodsByName = new HashMap<>();
        // TODO methods inherited by Object
        // TODO if we implement inheritance also other methods inherited from classes or interfaces
        for (Property property : getDirectProperties(resolver)) {
            {
                String descriptor = "()" + property.getTypeUsage().jvmType().getDescriptor();
                JvmMethodDefinition jvmMethodDefinition = new JvmMethodDefinition(getInternalName(), property.getterName(resolver), descriptor, false, false);
                InternalMethodDefinition getter = new InternalMethodDefinition(property.getterName(resolver), Collections.emptyList(), property.getTypeUsage(), jvmMethodDefinition);
                registerMethod(getter);
            }
            {
                String descriptor = "(" + property.getTypeUsage().jvmType().getDescriptor() + ")V";
                JvmMethodDefinition jvmMethodDefinition = new JvmMethodDefinition(getInternalName(), property.setterName(), descriptor, false, false);
                FormalParameterNode param = new FormalParameterNode(property.getTypeUsage().copy(), property.getName());
                param.setParent(this);
                InternalMethodDefinition setter = new InternalMethodDefinition(property.setterName(), ImmutableList.of(param), new VoidTypeUsageNode(), jvmMethodDefinition);
                registerMethod(setter);
            }
        }
    }

    public List<InternalConstructorDefinition> getConstructors() {
        if (constructors == null) {
            initializeConstructors(symbolResolver());
        }
        return constructors;
    }

    public InternalConstructorDefinition getOnlyConstructor(SymbolResolver resolver) {
        if (constructors == null) {
            initializeConstructors(resolver);
        }
        if (constructors.size() != 1) {
            throw new IllegalStateException();
        }
        return constructors.get(0);
    }

    private void initializeImplicitConstructor(SymbolResolver resolver) {
        List<? extends FormalParameter> inheritedParams = Collections.emptyList();
        if (getBaseType().isPresent()) {
            List<InternalConstructorDefinition> constructors = getBaseType().get().asReferenceTypeUsage().getTypeDefinition().getConstructors();
            if (constructors.size() != 1) {
                throw new UnsupportedOperationException();
            }
            inheritedParams = constructors.get(0).getFormalParameters();
        }

        List<FormalParameterNode> newParams = this.assignableProperties(resolver).stream()
                .map((p) -> new FormalParameterNode(p.getTypeUsage().copy(), p.getName(), p.getDefaultValue()))
                .collect(Collectors.toList());
        List<FormalParameter> allParams = new LinkedList<>();
        allParams.addAll(inheritedParams);
        allParams.addAll(newParams);
        allParams.sort(new Comparator<FormalParameter>() {
            @Override
            public int compare(FormalParameter o1, FormalParameter o2) {
                return Boolean.compare(o1.hasDefaultValue(), o2.hasDefaultValue());
            }
        });
        for (FormalParameter p : allParams) {
            // needed to solve symbols
            if (p.isNode()) {
                p.asNode().setParent(this);
            }
        }
        addConstructorWithParams(allParams, resolver);
    }

    private void initializeConstructors(SymbolResolver resolver) {
        constructors = new ArrayList<>();
        if (getExplicitConstructors().isEmpty()) {
            initializeImplicitConstructor(resolver);
        } else {
            if (getExplicitConstructors().size() > 1) {
                throw new IllegalStateException();
            }
            getExplicitConstructors().forEach((c)->initializeExplicitConstructor(c, resolver));
        }
    }

    private void addConstructorWithParams(List<? extends FormalParameter> allParams, SymbolResolver resolver) {
        List<FormalParameter> paramsWithoutDefaultValues = allParams.stream().filter((p)->!p.hasDefaultValue()).collect(Collectors.<FormalParameter>toList());
        List<String> paramSignatures = paramsWithoutDefaultValues.stream()
                .map((p) -> p.getType().jvmType().getSignature())
                .collect(Collectors.toList());
        boolean hasDefaultParameters = allParams.stream().filter((p)->p.hasDefaultValue()).findFirst().isPresent();
        if (hasDefaultParameters) {
            paramSignatures.add("Ljava/util/Map;");
        }
        JvmConstructorDefinition constructorDefinition = new JvmConstructorDefinition(jvmType().getInternalName(), "(" + String.join("", paramSignatures) + ")V");
        constructors.add(new InternalConstructorDefinition(new ReferenceTypeUsage(this), allParams, constructorDefinition));
    }

    private void initializeExplicitConstructor(TurinTypeContructorDefinitionNode constructor, SymbolResolver resolver) {
        List<? extends FormalParameter> allParams = constructor.getParameters();
        List<FormalParameter> paramsWithoutDefaultValues = allParams.stream().filter((p)->!p.hasDefaultValue()).collect(Collectors.<FormalParameter>toList());
        List<String> paramSignatures = paramsWithoutDefaultValues.stream()
                .map((p) -> p.getType().jvmType().getSignature())
                .collect(Collectors.toList());
        boolean hasDefaultParameters = allParams.stream().filter((p)->p.hasDefaultValue()).findFirst().isPresent();
        if (hasDefaultParameters) {
            paramSignatures.add("Ljava/util/Map;");
        }
        JvmConstructorDefinition constructorDefinition = new JvmConstructorDefinition(jvmType().getInternalName(), "(" + String.join("", paramSignatures) + ")V");
        constructors.add(new InternalConstructorDefinition(new ReferenceTypeUsage(this), allParams, constructorDefinition));
    }

    private void ensureIsInitialized(SymbolResolver resolver) {
        if (constructors == null) {
            initializeConstructors(resolver);
        }
        if (methodsByName == null) {
            initializeMethodsByName(resolver);
        }
    }

    @Override
    public JvmMethodDefinition findMethodFor(String methodName, List<JvmType> actualParams, boolean staticContext) {
        ensureIsInitialized(symbolResolver());
        List<InternalMethodDefinition> methods = methodsByName.get(methodName);
        if (methods.size() == 0) {
            throw new IllegalArgumentException("No method found with name " + methodName);
        } else if (methods.size() == 1) {
            if (methods.get(0).matchJvmTypes(symbolResolver(), actualParams)) {
                return methods.get(0).getJvmMethodDefinition();
            } else {
                throw new IllegalArgumentException("No method found with name " + methodName + " which matches " + actualParams);
            }
        } else {
            throw new IllegalStateException("No overloaded methods should be present in Turin types");
        }
    }

    private String getInternalName() {
        return JvmNameUtils.canonicalToInternal(getQualifiedName());
    }

    public void add(PropertyDefinition propertyDefinition){
        if (propertyDefinition.getType().getParent() != propertyDefinition && propertyDefinition.getType().getParent().getParent() == null) {
            throw new IllegalArgumentException();
        }
        members.add(propertyDefinition);
        propertyDefinition.parent = this;
    }

    public TurinTypeDefinition(String name) {
        super(name);
    }

    /**
     * Properties which can be referred to in the constructor
     */
    public List<Property> assignableProperties(SymbolResolver resolver) {
        return getDirectProperties(resolver).stream().filter((p)->!p.hasInitialValue()).collect(Collectors.toList());
    }

    public List<Property> propertiesAppearingInDefaultConstructor(SymbolResolver resolver) {
        return getDirectProperties(resolver).stream().filter((p) -> !p.hasInitialValue() && !p.hasDefaultValue()).collect(Collectors.toList());
    }

    public List<Property> defaultPropeties(SymbolResolver resolver) {
        return getDirectProperties(resolver).stream().filter((p) -> p.hasDefaultValue()).collect(Collectors.toList());
    }

    public boolean hasDefaultProperties(SymbolResolver resolver) {
        return getDirectProperties(resolver).stream().filter((p)->p.hasDefaultValue()).findFirst().isPresent();
    }

    @Override
    public JvmConstructorDefinition resolveConstructorCall(List<ActualParam> actualParams) {
        // all named parameters should be after the named ones
        if (!ParamUtils.verifyOrder(actualParams)) {
            throw new IllegalArgumentException("Named params should all be grouped after the positional ones");
        }

        ensureIsInitialized(symbolResolver());
        Optional<InternalConstructorDefinition> constructor = constructors.stream().filter((c)->c.match(symbolResolver(), actualParams)).findFirst();

        if (!constructor.isPresent()){
            throw new UnsolvedConstructorException(getQualifiedName(), actualParams);
        }

        return constructor.get().getJvmConstructorDefinition();
    }

    @Override
    public TypeUsageNode getFieldType(String fieldName, boolean staticContext) {
        for (Property property : getAllProperties(symbolResolver())) {
            if (property.getName().equals(fieldName)) {
                return property.getTypeUsage();
            }
        }
        throw new IllegalArgumentException(fieldName);
    }

    @Override
    public List<ReferenceTypeUsage> getAllAncestors() {
        if (getBaseType().isPresent()) {
            List<ReferenceTypeUsage> res = new ArrayList<>();
            res.add(getBaseType().get().asReferenceTypeUsage());
            res.addAll(getBaseType().get().asReferenceTypeUsage().getAllAncestors());
            return res;
        }
        return ImmutableList.of(ReferenceTypeUsage.OBJECT(symbolResolver()));
    }

    @Override
    public boolean isInterface() {
        // TODO when it will be possible to declare interface fix this
        return false;
    }

    @Override
    public boolean isClass() {
        // TODO when it will be possible to declare interface fix this
        return true;
    }

    @Override
    public Optional<InternalMethodDefinition> findMethod(String methodName, List<ActualParam> actualParams,boolean staticContext) {
        // all named parameters should be after the named ones
        if (!ParamUtils.verifyOrder(actualParams)) {
            throw new IllegalArgumentException("Named params should all be grouped after the positional ones");
        }

        ensureIsInitialized(symbolResolver());
        if (!methodsByName.containsKey(methodName)) {
            return Optional.empty();
        }
        Optional<InternalMethodDefinition> method = methodsByName.get(methodName).stream().filter((m)->m.match(symbolResolver(), actualParams)).findFirst();
        return method;
    }

    @Override
    public boolean hasField(String name, boolean staticContext) {
        throw new UnsupportedOperationException();
    }

    public void add(PropertyReference propertyReference) {
        members.add(propertyReference);
        propertyReference.parent = this;
    }

    @Override
    public String toString() {
        return "TypeDefinition{" +
                "name='" + name + '\'' +
                ", members=" + members +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TurinTypeDefinition that = (TurinTypeDefinition) o;

        if (!members.equals(that.members)) return false;
        if (!name.equals(that.name)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + members.hashCode();
        return result;
    }

    @Override
    public Optional<Symbol> findSymbol(String name, SymbolResolver resolver) {
        // TODO support references to methods
        for (Property property : this.getAllProperties(resolver)) {
            if (property.getName().equals(name)) {
                return Optional.of(property);
            }
        }

        return super.findSymbol(name, resolver);
    }

    /**
     * Does it override the toString method defined in Object?
     */
    public boolean defineMethodToString(SymbolResolver resolver) {
        return isDefiningMethod("toString", Collections.emptyList(), resolver);
    }

    /**
     * Does it override the hashCode method defined in Object?
     */
    public boolean defineMethodHashCode(SymbolResolver resolver) {
        return isDefiningMethod("hashCode", Collections.emptyList(), resolver);
    }

    /**
     * Does it override the equals method defined in Object?
     */
    public boolean defineMethodEquals(SymbolResolver resolver) {
        return isDefiningMethod("equals", ImmutableList.of(ReferenceTypeUsage.OBJECT(resolver)), resolver);
    }

    private boolean isDefiningMethod(String name, List<TypeUsage> paramTypes, SymbolResolver resolver) {
        return getDirectMethods().stream().filter((m)->m.getName().equals(name))
                .filter((m) -> m.getParameters().stream().map((p) -> p.calcType().jvmType()).collect(Collectors.toList())
                        .equals(paramTypes.stream().map((p) -> p.jvmType()).collect(Collectors.toList())))
                .count() > 0;
    }

    public List<AnnotationUsage> getAnnotations() {
        return annotations;
    }

    @Override
    public Iterable<Node> getChildren() {
        List<Node> children = new LinkedList<>();
        children.addAll(members);
        children.addAll(annotations);
        if (baseType.isPresent()) {
            children.add(baseType.get());
        }
        children.addAll(interfaces);
        return children;
    }

    public List<Property> getDirectProperties(SymbolResolver resolver) {
        List<Property> properties = new ArrayList<>();
        for (Node member : members) {
            if (member instanceof PropertyDefinition) {
                properties.add(Property.fromDefinition((PropertyDefinition)member));
            } else if (member instanceof PropertyReference) {
                properties.add(Property.fromReference((PropertyReference) member, resolver));
            }
        }
        return properties;
    }

    public List<TurinTypeMethodDefinitionNode> getDirectMethods() {
        List<TurinTypeMethodDefinitionNode> methods = new ArrayList<>();
        for (Node member : members) {
            if (member instanceof TurinTypeMethodDefinitionNode) {
                methods.add((TurinTypeMethodDefinitionNode)member);
            }
        }
        return methods;
    }

    public List<TypeUsageNode> getInterfaces() {
        return interfaces;
    }

    public Optional<TypeUsageNode> getBaseType() {
        return baseType;
    }

    /**
     * Get direct and inherited properties.
     */
    public List<Property> getAllProperties(SymbolResolver resolver) {
        // TODO consider also inherited properties
        return getDirectProperties(resolver);
    }

    public void add(TurinTypeMethodDefinitionNode methodDefinition) {
        members.add(methodDefinition);
        methodDefinition.parent = this;
    }

    public void add(TurinTypeContructorDefinitionNode contructorDefinition) {
        members.add(contructorDefinition);
        contructorDefinition.parent = this;
    }

    @Override
    public boolean canFieldBeAssigned(String field) {
        return true;
    }

    public boolean defineExplicitConstructor(SymbolResolver resolver) {
        return !getExplicitConstructors().isEmpty();
    }

    @Override
    public TypeDefinition getSuperclass() {
        if (this.baseType.isPresent()) {
            return this.baseType.get().asReferenceTypeUsage().getTypeDefinition();
        }
        return ReflectionTypeDefinitionFactory.getInstance().getTypeDefinition(Object.class, symbolResolver());
    }

    @Override
    public <T extends TypeUsage> Map<String, TypeUsage> associatedTypeParametersToName(List<T> typeParams) {
        return Collections.emptyMap();
    }

    @Override
    public Optional<InternalConstructorDefinition> findConstructor(List<ActualParam> actualParams) {
        ensureIsInitialized(symbolResolver());
        for (InternalConstructorDefinition constructor : constructors) {
            if (constructor.match(symbolResolver(), actualParams)) {
                return Optional.of(constructor);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Invokable> getMethod(String method, boolean staticContext, Map<String, TypeUsage> typeParams) {
        ensureIsInitialized(symbolResolver());
        Set<InternalMethodDefinition> methods = Collections.emptySet();
        if (methodsByName.containsKey(method)) {
            methods = methodsByName.get(method).stream()
                    .filter((m) -> m.getJvmMethodDefinition().isStatic() == staticContext)
                    .collect(Collectors.toSet());
        }
        if (methods.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(new MethodSetAsInvokableType(methods, typeParams));
        }
    }

}
