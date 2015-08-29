package me.tomassetti.turin.ast;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

public class TypeDefinition extends Node {
    private String name;
    private List<Node> members = new ArrayList<>();

    public void add(PropertyDefinition propertyDefinition){
        members.add(propertyDefinition);
    }

    public TypeDefinition(String name) {
        this.name = name;
    }

    public ImmutableList<Node> getMembers() {
        return ImmutableList.copyOf(members);
    }

    public void add(PropertyReference propertyReference) {
        members.add(propertyReference);
    }

    @Override
    public Iterable<Node> getChildren() {
        return ImmutableList.copyOf(members);
    }
}
