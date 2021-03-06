/*
 * Orika - simpler, better and faster Java bean mapping
 *
 * Copyright (C) 2011-2013 Orika authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ma.glasnost.orika.impl.generator.specification;

import static java.lang.String.format;
import static ma.glasnost.orika.impl.generator.SourceCodeContext.append;
import static ma.glasnost.orika.impl.generator.SourceCodeContext.join;
import static ma.glasnost.orika.impl.generator.SourceCodeContext.statement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ma.glasnost.orika.MapperFactory;
import ma.glasnost.orika.impl.generator.AggregateSpecification;
import ma.glasnost.orika.impl.generator.Node;
import ma.glasnost.orika.impl.generator.Node.NodeList;
import ma.glasnost.orika.impl.generator.SourceCodeContext;
import ma.glasnost.orika.impl.generator.VariableRef;
import ma.glasnost.orika.impl.util.ClassUtil;
import ma.glasnost.orika.metadata.ClassMapBuilder;
import ma.glasnost.orika.metadata.FieldMap;
import ma.glasnost.orika.metadata.MapperKey;
import ma.glasnost.orika.metadata.Property;
import ma.glasnost.orika.metadata.Type;
import ma.glasnost.orika.metadata.TypeFactory;

/**
 * MultiOccurrenceToMultiOccurrence handles the mapping of one or more
 * multi-occurrence source fields to one or more multi-occurrence destination
 * fields.
 */
public class MultiOccurrenceToMultiOccurrence implements AggregateSpecification {
    
    /**
     * The MapperFactory relevant to this code generation request
     */
    protected MapperFactory mapperFactory;
    
    /**
     * Generates the code to support a (potentially parallel) mapping from one
     * or more multi-occurrence fields in the source type to one or more
     * multi-occurrence fields in the destination type.
     * 
     * @param fieldMappings
     *            the field mappings to be applied
     * @param code 
     * @param logDetails
     *            a StringBuilder to accept debug logging information
     * @return a reference to <code>this</code> SourceCodeBuilder
     */
    public String fromMultiOccurrenceToMultiOccurrence(List<FieldMap> fieldMappings, SourceCodeContext code) {
        
        StringBuilder out = new StringBuilder();
        while (!fieldMappings.isEmpty()) {
            Set<FieldMap> associated = code.getAssociatedMappings(fieldMappings, fieldMappings.get(0));
            fieldMappings.removeAll(associated);
            
            NodeList sourceNodes = new NodeList();
            NodeList destNodes = new NodeList();
            
            for (FieldMap map : associated) {

                Node.addFieldMap(map, sourceNodes, true);
                Node.addFieldMap(map, destNodes, false);
            }    
              
            registerClassMaps(sourceNodes, destNodes);
            
            out.append(generateMultiOccurrenceMapping(sourceNodes, destNodes, associated, code));
        }
        return out.toString();
    }
    
    /**
     * Generates the code to support a (potentially parallel) mapping from one
     * or more multi-occurrence fields in the source type to one or more
     * multi-occurrence fields in the destination type.
     * @param sourceNodes 
     * @param destNodes 
     * 
     * @param sources
     *            the associated source variables
     * @param destinations
     *            the associated destination variables
     * @param subFields
     *            the nested properties of the individual field maps
     * @param code 
     * @param logDetails
     *            a StringBuilder to accept debug logging information
     * @return a reference to <code>this</code> CodeSourceBuilder
     */
    public String generateMultiOccurrenceMapping(NodeList sourceNodes, NodeList destNodes,
            Set<FieldMap> subFields, SourceCodeContext code) {
        
        StringBuilder out = new StringBuilder();
        StringBuilder sourcesNotNull = new StringBuilder();
        
        /*
         * Construct size/length expressions used to limit the parallel iteration
         * of multiple source variables; only keep iterating so long as all variables
         * in a parallel set are non-empty
         */
        List<String> sourceSizes = new ArrayList<String>();
        for (Node ref : sourceNodes) {
            if (!ref.isLeaf()) {
                sourceSizes.add(ref.multiOccurrenceVar.size());
                if (sourcesNotNull.length() > 0) {
                    sourcesNotNull.append(" && ");
                }
                sourcesNotNull.append(ref.multiOccurrenceVar.notNull());
            }
        }
        
        String sizeExpr = join(sourceSizes, ", ");
        if (!"".equals(sizeExpr)) {
            sizeExpr = "min(new int[]{" + sizeExpr + "})";
        }
        
        /*
         * Declare "collector" elements and their iterators; used for aggregating
         * results which are finally assigned/copied back into their final destination(s)
         */
        for (Node destRef : destNodes) {
            
            if (!destRef.isLeaf()) {
                out.append(statement(destRef.newDestination.declare()));
                if (!"".equals(sourcesNotNull.toString())) {
                    out.append( 
                            statement("if (%s) {\n%s;\n} else {\n%s;}", 
                                    sourcesNotNull, 
                                    destRef.newDestination.assign(destRef.newDestination.newInstance(sizeExpr)),
                                    destRef.newDestination.assign("null")
                            ));
                } else {
                    /*
                     * We're not able to check the sources for null
                     */
                    out.append( 
                            statement(destRef.newDestination.assign("null")
                            ));
                }
                if (destRef.newDestination.isArray()) {
                    out.append(statement(destRef.newDestination.declareIterator()));
                }
                List<Node> children = new ArrayList<Node>();
                children.add(destRef);
                while (!children.isEmpty()) {
                    Node child = children.remove(0);
                    children.addAll(child.children);
                    if (child.elementRef != null) {
                        out.append(statement(child.elementRef.declare()));
                        if (child.multiOccurrenceVar.isArray()) {
                            out.append(statement(child.multiOccurrenceVar.declareIterator()));
                        }
                        if (child.elementRef.isPrimitive()) {
                            out.append(statement(child.nullCheckFlag.declare("true")));
                        }
                        out.append(statement(child.shouldAddToCollectorFlag.declare("false")));
                    }
                }
            }
        }
        
        StringBuilder endWhiles = new StringBuilder();
        StringBuilder addLastElement = new StringBuilder();
        
        iterateSources(sourceNodes, destNodes, out, endWhiles);
        
        LinkedList<Node> stack = new LinkedList<Node>(destNodes);
        while (!stack.isEmpty()) {
            
            Node currentNode = stack.removeFirst();
            stack.addAll(0, currentNode.children);
            Node srcNode = null;
            if (currentNode.value != null) {
                srcNode = Node.findFieldMap(currentNode.value, sourceNodes, true);
            } else {
                FieldMap fieldMap = currentNode.getMap();
                if (fieldMap != null) {
                    srcNode = Node.findFieldMap(fieldMap, sourceNodes, true).parent;
                }
            }
            
            if (!currentNode.isLeaf() && srcNode != null) { 
                /*
                 * Add a comparison for the next source element; if it is "different" than 
                 * it's destination (determined by custom comparator we've generated), then
                 * we create a new element and add it to the destination collector.
                 */
                String currentElementNull = currentNode.elementRef.isPrimitive() ? currentNode.nullCheckFlag.toString() : currentNode.elementRef.isNull();
                String currentElementComparator = code.currentElementComparator(srcNode, currentNode, sourceNodes, destNodes);
                String or = (!"".equals(currentElementNull) && !"".equals(currentElementComparator)) ? " || " : "";
                
                if (mapperFactory.getConverterFactory().canConvert(srcNode.elementRef.type(), currentNode.elementRef.type()) //) {
                        || ClassUtil.isImmutable(currentNode.elementRef.type())) {
                
                    append(out,
                            (currentNode.elementRef.isPrimitive() ? currentNode.nullCheckFlag.assign("false") : ""),
                            currentNode.shouldAddToCollectorFlag.assign("true")
                            );
                    
                
                } else {
                
                    append(out,
                            "if ( " + currentElementNull + or + currentElementComparator + ") {\n",
                            currentNode.elementRef.assign(code.newObject(srcNode.elementRef, currentNode.elementRef.type())),
                            currentNode.shouldAddToCollectorFlag.assign("true"),
                            "}");
                }
            }
            
            if (srcNode != null) {
                if (currentNode.value != null) {
                    
                    /*
                     * If we have a fieldMap for the current node, attempt to map the fields
                     */
                    boolean wasConverted = mapFields(currentNode, srcNode, out, code);
                    if (srcNode.parent != null 
                            && srcNode.parent.elementRef != null
                            && currentNode.parent != null 
                            && currentNode.parent.elementRef != null 
                            && !currentNode.parent.addedToCollector) {
                        
                        String assignNull = (currentNode.parent.elementRef.isPrimitive() ? currentNode.parent.nullCheckFlag.assign("true") : currentNode.parent.elementRef.assign("null"));
                        if (mapperFactory.getConverterFactory().canConvert(srcNode.parent.elementRef.type(), currentNode.parent.elementRef.type())) {
                            append(out,
                                    (currentNode.parent.isRoot() ? currentNode.parent.newDestination.add(currentNode.parent.elementRef) : currentNode.parent.multiOccurrenceVar.add(currentNode.parent.elementRef)),
                                    assignNull
                                    );
                        } else {
                            append(out,
                                    format("if (%s) {", currentNode.parent.shouldAddToCollectorFlag),
                                    (currentNode.parent.isRoot() ? currentNode.parent.newDestination.add(currentNode.parent.elementRef) : currentNode.parent.multiOccurrenceVar.add(currentNode.parent.elementRef)),
                                    currentNode.parent.shouldAddToCollectorFlag.assign("false"),
                                    (wasConverted ? assignNull : ""),
                                    "}");
                        }
                        currentNode.parent.addedToCollector = true;
                    }
                } else {
                    VariableRef s = makeVariable(srcNode.property, srcNode, "source");
                    VariableRef d = makeVariable(currentNode.property, currentNode, "destination");
                    code.applyFilters(s, d, out, endWhiles);
    
    
                    d = currentNode.isRoot() ? currentNode.newDestination : currentNode.multiOccurrenceVar;
                    out.append(format("\nmappingContext.beginMapping(%s, %s, %s, %s);\n",
                                code.usedType(s.type()),
                                s.asWrapper(),
                                code.usedType(d.type()),
                                d.asWrapper()));
                    out.append("try {\n");
                    endWhiles.insert(0, "\n} finally {\n  mappingContext.endMapping();\n}\n");
                }
            }
        }  
        
        out.append(endWhiles.toString());
        out.append(addLastElement.toString());
        
        /*
         * Finally, we loop over the destination nodes and assign/copy all of the temporary 
         * "collector" variables back into their final destination
         */
        for (Node destRef : destNodes) {
            if (destRef.isRoot() && !destRef.isLeaf()) {
                if (destRef.multiOccurrenceVar.isArray() || destRef.multiOccurrenceVar.isMap()) {
                    /*
                     * We use a List as the temporary collector element for Arrays and Maps
                     */
                    append(out,
                            format("if (%s && %s) {",destRef.newDestination.notNull(), destRef.newDestination.notEmpty()),
                            destRef.multiOccurrenceVar.addAll(destRef.newDestination),
                            "}\n");
                } else {
                    append(out,
                            format("if (%s && %s) {",destRef.newDestination.notNull(), destRef.newDestination.notEmpty()),
                            format("if (%s) {", destRef.multiOccurrenceVar.isNull()),
                            destRef.multiOccurrenceVar.assignIfPossible(destRef.multiOccurrenceVar.newInstance(sizeExpr)),
                            "} else {\n",
                            destRef.multiOccurrenceVar + ".clear()",
                            "}\n",
                            destRef.multiOccurrenceVar.addAll(destRef.newDestination),
                            "}\n");
                }
            }
        }
        
        return out.toString();
    }
    
    private Property innermostElement(final Property p) {
        Property result = p;
        while (result.getElement() != null) {
            result = result.getElement();
        }
        return result;
    }
    
    private boolean mapFields(Node currentNode, Node srcNode, StringBuilder out, SourceCodeContext code) {
        VariableRef s = makeVariable(currentNode.value.getSource(), srcNode, "source");
        VariableRef d = makeVariable(currentNode.value.getDestination(), currentNode, "destination");
        
        Type<?> destType = currentNode.parent != null ? currentNode.parent.elementRef.type() : null;
        
        String mapStmt = statement(code.mapFields(currentNode.value, s, d, destType, null));
        VariableRef elRef = currentNode.parent != null ? currentNode.parent.elementRef : null;
        if (elRef != null && !elRef.isPrimitive() && !ClassUtil.isImmutable(elRef.type()) && srcNode.parent != null) {
            // assure instance exists for the element reference
            append(out, format("if((%s)) { \n", elRef.isNull()), elRef.assign(code.newObject(srcNode.parent.elementRef, elRef.type())), "}");
        }
        out.append(mapStmt);
        
        Type<?> parentElementType = currentNode.parent != null ? currentNode.parent.elementRef.type() : TypeFactory.TYPE_OF_OBJECT;
        
        return d.type().equals(parentElementType) && mapperFactory.getConverterFactory().canConvert(s.type(), d.type());
    }
    
    private VariableRef makeVariable(Property currentProp, Node node, String defName) {
        String name = node.parent != null ? node.parent.elementRef.name() : defName;

        Property p = innermostElement(currentProp);
        Property prop = new Property.Builder().merge(p).expression(p.getExpression()).build();

        return new VariableRef(prop, name);
    }
    
    /**
     * Register the ClassMaps needed to map this pair of source and
     * destination nodes.
     * 
     * @param sourceNodes
     * @param destNodes
     */
    private void registerClassMaps(NodeList sourceNodes, NodeList destNodes) {
        /*
         * Register all of the subordinate ClassMaps needed by this multi-occurrence
         * mapping 
         */
        Map<MapperKey, ClassMapBuilder<?,?>> builders = new HashMap<MapperKey, ClassMapBuilder<?,?>>();
        
        LinkedList<Node> stack = new LinkedList<Node>(destNodes);
        while (!stack.isEmpty()) {
            
            Node currentNode = stack.removeFirst();
            stack.addAll(0, currentNode.children);
            Node srcNode = null;
            if (currentNode.value != null) {
                srcNode = Node.findFieldMap(currentNode.value, sourceNodes, true);
            } else {
                FieldMap fieldMap = currentNode.getMap();
                if (fieldMap != null) {
                    srcNode = Node.findFieldMap(fieldMap, sourceNodes, true).parent;
                }
            }
        
            if (srcNode != null 
                    && srcNode.parent != null 
                    && srcNode.parent.elementRef != null
                    && currentNode != null
                    && currentNode.parent != null 
                    && currentNode.parent.elementRef != null) {
                
                MapperKey key = new MapperKey(srcNode.parent.elementRef.type(), currentNode.parent.elementRef.type());
                
                /*
                 * 
                 */
                if (!ClassUtil.isImmutable(key.getAType()) 
                        && !ClassUtil.isImmutable(key.getBType()) 
                        && !mapperFactory.existsRegisteredMapper(key.getAType(), key.getBType(), true)
                        && mapperFactory.getClassMap(key) == null) {
                    ClassMapBuilder<?,?> builder = builders.get(key);
                    if (builder == null) {
                        builder = mapperFactory.classMap(key.getAType(), key.getBType());
                        builders.put(key, builder);
                    }
                    Property sp = innermostElement(currentNode.value.getSource());
                    Property dp = innermostElement(currentNode.value.getDestination()); 
                    builder.fieldMap(sp.getExpression(), dp.getExpression()).add();
                }
            }
        }
        
        
        for (ClassMapBuilder<?,?> builder: builders.values()) {
            builder.register();
        }
    }
    
    /**
     * Creates the looping constructs for nested source variables
     * 
     * @param sourceNodes
     * @param destNodes
     * @param out
     * @param endWhiles
     */
    private void iterateSources(NodeList sourceNodes, NodeList destNodes, StringBuilder out, StringBuilder endWhiles) {
        
        if (!sourceNodes.isEmpty()) {
            for (Node srcRef : sourceNodes) {
                if (!srcRef.isLeaf()) {
                    out.append(srcRef.multiOccurrenceVar.ifNotNull()).append(" {\n");
                    endWhiles.append("\n}");
                    out.append(statement(srcRef.multiOccurrenceVar.declareIterator()));
                }
            }
            
            StringBuilder loopSource = new StringBuilder();
            /*
             * Create while loop for the top level multi-occurrence objects
             */
            loopSource.append("while (");
            Iterator<Node> sourcesIter = sourceNodes.iterator();
            boolean atLeastOneIter = false;
            while (sourcesIter.hasNext()) {
                Node ref = sourcesIter.next();
                if (!ref.isLeaf()) {
                    if (atLeastOneIter) {
                        loopSource.append(" && ");
                    }
                    loopSource.append(ref.multiOccurrenceVar.iteratorHasNext());
                    atLeastOneIter = true;
                }
            }
            loopSource.append(") {");
            
            if (atLeastOneIter) {
                out.append("\n");
                out.append(loopSource.toString());
            }
            for (Node srcRef : sourceNodes) {
                
                if (!srcRef.isLeaf()) {
                    out.append(statement(srcRef.elementRef.declare(srcRef.multiOccurrenceVar.nextElementRef())));
                    iterateSources(srcRef.children, destNodes, out, endWhiles);
                }
            }
            if (atLeastOneIter) {
                endWhiles.append("}\n");
            }
        }
    }

    /* (non-Javadoc)
     * @see ma.glasnost.orika.impl.generator.AggregateSpecification#appliesTo(ma.glasnost.orika.metadata.FieldMap)
     */
    public boolean appliesTo(FieldMap fieldMap) {
        return fieldMap.getSource().getContainer() != null || fieldMap.getDestination().getContainer() != null;
    }

    /* (non-Javadoc)
     * @see ma.glasnost.orika.impl.generator.AggregateSpecification#generateMappingCode(java.util.Set, ma.glasnost.orika.impl.generator.SourceCode)
     */
    public String generateMappingCode(List<FieldMap> fieldMappings, SourceCodeContext code) {
        return this.fromMultiOccurrenceToMultiOccurrence(fieldMappings, code);
    }

    /* (non-Javadoc)
     * @see ma.glasnost.orika.impl.generator.AggregateSpecification#setMapperFactory(ma.glasnost.orika.MapperFactory)
     */
    public void setMapperFactory(MapperFactory mapperFactory) {
        this.mapperFactory = mapperFactory;
    }
}
