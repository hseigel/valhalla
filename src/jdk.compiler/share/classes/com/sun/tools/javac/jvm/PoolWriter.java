/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.javac.jvm;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.DynamicMethodSymbol;
import com.sun.tools.javac.code.Symbol.MethodHandleSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symbol.PackageSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ConstantPoolQType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.jvm.ClassWriter.PoolOverflow;
import com.sun.tools.javac.jvm.ClassWriter.StringOverflow;
import com.sun.tools.javac.jvm.PoolConstant.Linkage;
import com.sun.tools.javac.jvm.PoolConstant.LoadableConstant;
import com.sun.tools.javac.jvm.PoolConstant.LoadableConstant.BasicConstant;
import com.sun.tools.javac.jvm.PoolConstant.Dynamic;
import com.sun.tools.javac.jvm.PoolConstant.Dynamic.BsmKey;
import com.sun.tools.javac.jvm.PoolConstant.NameAndType;
import com.sun.tools.javac.jvm.PoolConstant.Parameter;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.ByteBuffer;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.sun.tools.javac.code.Kinds.Kind.TYP;
import static com.sun.tools.javac.code.TypeTag.ARRAY;
import static com.sun.tools.javac.code.TypeTag.CLASS;
import static com.sun.tools.javac.jvm.ClassFile.CONSTANT_Class;
import static com.sun.tools.javac.jvm.ClassFile.CONSTANT_MethodType;
import static com.sun.tools.javac.jvm.ClassFile.externalize;

/**
 * Pool interface towards {@code ClassWriter}. Exposes methods to encode and write javac entities
 * into the constant pool.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class PoolWriter {

    /** Max number of constant pool entries. */
    public static final int MAX_ENTRIES = 0xFFFF;

    /** Max number of char in a string constant. */
    public static final int MAX_STRING_LENGTH = 0xFFFF;

    private static final int POOL_BUF_SIZE = 0x7fff;

    private final Types types;

    private final Names names;

    private final Symtab syms;

    private final Log log;

    private final boolean supportParametricVM;

    /** Pool helper **/
    final WriteablePoolHelper pool;

    /** Sole signature generator */
    final SharedSignatureGenerator signatureGen;

    /** The inner classes to be written, as an ordered set (enclosing first). */
    LinkedHashSet<ClassSymbol> innerClasses = new LinkedHashSet<>();

    /** The list of entries in the BootstrapMethods attribute. */
    Map<BsmKey, Integer> bootstrapMethods = new LinkedHashMap<>();

    // current symbol for which the code is being generated
    Symbol.MethodSymbol currentMethSymbol;

    public PoolWriter(Types types, Names names, Symtab syms, Log log, boolean supportParametricVM) {
        this.types = types;
        this.names = names;
        this.syms = syms;
        this.log = log;
        this.signatureGen = new SharedSignatureGenerator(types);
        this.pool = new WriteablePoolHelper();
        this.supportParametricVM = supportParametricVM;
    }

    /**
     * Puts a class symbol into the pool and return its index.
     */
    int putClass(ClassSymbol csym) {
        return pool.writeIfNeeded(wrapWithLinkageIfNeeded(types.erasure(csym.type), csym.attribute(syms.parametricType.tsym)));
    }

    /**
     * Puts a type into the pool and return its index. The type could be either a class, a type variable
     * or an array type.
     */
    int putClass(Type t) {
        return pool.writeIfNeeded(wrapWithLinkageIfNeeded(types.erasure(t), t.tsym.attribute(syms.parametricType.tsym)));
    }

    /**
     * Puts a type into the pool and return its index. The type could be either a class, a type variable
     * or an array type.
     */
    int putClass(ConstantPoolQType t) {
        // still need to check here if linkage is necessary
        return pool.writeIfNeeded(t);
    }

    Set<Object> referencesBeingLinked = new HashSet<>();

    /**
     * Puts a member reference into the constant pool. Valid members are either field or method symbols.
     */
    int putMember(Symbol s) {
        return pool.writeIfNeeded(wrapWithLinkageIfNeeded(s, s.attribute(syms.parametricType.tsym)));
    }

    // where
        boolean enclMethodHasLinkageAnno() {
            return currentMethSymbol != null && (currentMethSymbol.attribute(syms.linkageMethodType.tsym) != null ||
                    currentMethSymbol.attribute(syms.linkageClassType.tsym) != null);
        }

        /* this method will try to generate a Linkage that will eventually be written into the constant pool
         * if it can't find all the needed information it will bail out, and return -1, to inform the caller
         * to generate the corresponding legacy entry. If successful it will return the index of the corresponding
         * Linkage_info constant in the constant pool
         */
        PoolConstant wrapWithLinkageIfNeeded(PoolConstant poolConstant, Attribute.Compound parametricAnno) {
            // the referred element is parametric
            if (!supportParametricVM ||
                    parametricAnno == null ||
                    !enclMethodHasLinkageAnno() ||
                    referencesBeingLinked.contains(poolConstant)) {
                return poolConstant;
            }
            Name kindName = names.CLASS;
            Attribute value = parametricAnno.member(names.kind);
            if (value != null && value instanceof Attribute.Enum) {
                kindName = ((Attribute.Enum)value).value.name;
            }

            // let's find out if the enclosing method has any linkage annotations
            String linkageMethodValueStr = null;
            Attribute.Compound linkageMethodAnno = currentMethSymbol.attribute(syms.linkageMethodType.tsym);
            if (linkageMethodAnno != null) {
                Attribute linkageMethodValue = linkageMethodAnno.member(names.value);
                if (linkageMethodValue != null && linkageMethodValue instanceof Attribute.Constant) {
                    linkageMethodValueStr = linkageMethodValue.getValue().toString();
                }
                if (linkageMethodValueStr == null) {
                    log.printRawLines("LinkageMethod annotation without value");
                    return poolConstant;
                }
            }
            String linkageClassValueStr = null;
            Attribute.Compound linkageClassAnno = currentMethSymbol.attribute(syms.linkageClassType.tsym);
            if (linkageClassAnno != null) {
                Attribute linkageClassValue = linkageClassAnno.member(names.value);
                if (linkageClassValue != null && linkageClassValue instanceof Attribute.Constant) {
                    linkageClassValueStr = linkageClassValue.getValue().toString();
                }
                if (linkageClassValueStr == null) {
                    log.printRawLines("LinkageClass annotation without value");
                    return poolConstant;
                }
            }
            if (kindName == names.METHOD_ONLY) {
                if (linkageMethodValueStr == null) {
                    // bail out
                    return poolConstant;
                }
                referencesBeingLinked.add(poolConstant);
                return new Linkage(linkageMethodValueStr, poolConstant, false);
            } else if (kindName == names.CLASS) {
                if (linkageClassValueStr == null) {
                    // bail out
                    return poolConstant;
                }
                referencesBeingLinked.add(poolConstant);
                return new Linkage(linkageClassValueStr, poolConstant, poolConstant instanceof Type);
            } else if (kindName == names.METHOD_AND_CLASS) {
                if (linkageMethodValueStr == null) {
                    // bail out
                    return poolConstant;
                }
                referencesBeingLinked.add(poolConstant);
                return new Linkage(linkageMethodValueStr, poolConstant, poolConstant instanceof Type);
            }
            log.printRawLines("could not generate Linkage_info constant");
            return poolConstant;
        }

    /**
     * Puts a dynamic reference into the constant pool and return its index.
     */
    int putDynamic(Dynamic d) {
        return pool.writeIfNeeded(d);
    }

    /**
     * Puts a field or method descriptor into the constant pool and return its index.
     */
    int putDescriptor(Type t) {
        return putName(typeSig(types.erasure(t)));
    }

    /**
     * Puts a field or method descriptor into the constant pool and return its index.
     */
    int putDescriptor(Symbol s) {
        return putDescriptor(descriptorType(s));
    }

    /**
     * Puts a signature (see {@code Signature} attribute in JVMS 4.4) into the constant pool and
     * return its index.
     */
    int putSignature(Symbol s) {
        if (s.kind == TYP) {
            return putName(classSig(s.type));
        } else {
            return putName(typeSig(s.type));
        }
    }

    /**
     * Puts a constant value into the pool and return its index. Supported values are int, float, long,
     * double and String.
     */
    int putConstant(Object o) {
        if (o instanceof Integer) {
            return putConstant(LoadableConstant.Int((int)o));
        } else if (o instanceof Float) {
            return putConstant(LoadableConstant.Float((float)o));
        } else if (o instanceof Long) {
            return putConstant(LoadableConstant.Long((long)o));
        } else if (o instanceof Double) {
            return putConstant(LoadableConstant.Double((double)o));
        } else if (o instanceof String) {
            return putConstant(LoadableConstant.String((String)o));
        } else {
            throw new AssertionError("unexpected constant: " + o);
        }
    }

    /**
     * Puts a constant into the pool and return its index.
     */
    int putConstant(LoadableConstant c) {
        switch (c.poolTag()) {
            case CONSTANT_Class:
                return putClass((Type)c);
            case CONSTANT_MethodType:
                return pool.writeIfNeeded(types.erasure((Type)c));
            default:
                return pool.writeIfNeeded(c);
        }
    }

    int putName(Name name) {
        return pool.writeIfNeeded(name);
    }

    /**
     * Puts a name and type pair into the pool and returns its index.
     */
    int putNameAndType(Symbol s) {
        return pool.writeIfNeeded(new NameAndType(s.name, descriptorType(s)));
    }

    /**
     * Puts a parameter into the pool and returns its index.
     */
    int putParameter(String id, int kind) {
        return pool.writeIfNeeded(new Parameter(id, kind));
    }

    /**
     * Puts a package entry into the pool and returns its index.
     */
    int putPackage(PackageSymbol pkg) {
        return pool.writeIfNeeded(pkg);
    }

    /**
     * Puts a module entry into the pool and returns its index.
     */
    int putModule(ModuleSymbol mod) {
        return pool.writeIfNeeded(mod);
    }

    /**
     * Enter an inner class into the `innerClasses' set.
     */
    void enterInner(ClassSymbol c) {
        if (c.type.isCompound()) {
            throw new AssertionError("Unexpected intersection type: " + c.type);
        }
        c.complete();
        if (c.owner.enclClass() != null && !innerClasses.contains(c)) {
            enterInner(c.owner.enclClass());
            innerClasses.add(c);
        }
    }

    /**
     * Create a new Utf8 entry representing a descriptor for given (member) symbol.
     */
    private Type descriptorType(Symbol s) {
        return s.kind == Kind.MTH ? s.externalType(types) : s.erasure(types);
    }

    private int makeBootstrapEntry(Dynamic dynamic) {
        BsmKey bsmKey = dynamic.bsmKey(types);

        // Figure out the index for existing BSM; create a new BSM if no key
        Integer index = bootstrapMethods.get(bsmKey);
        if (index == null) {
            index = bootstrapMethods.size();
            bootstrapMethods.put(bsmKey, index);
        }

        return index;
    }

    /**
     * Write pool contents into given byte buffer.
     */
    void writePool(OutputStream out) throws IOException, PoolOverflow {
        if (pool.overflowString != null) {
            throw new StringOverflow(pool.overflowString);
        }
        int size = size();
        if (size > MAX_ENTRIES) {
            throw new PoolOverflow();
        }
        out.write(size >> 8);
        out.write(size);
        out.write(pool.poolbuf.elems, 0, pool.poolbuf.length);
    }

    /**
     * Signature Generation
     */
    class SharedSignatureGenerator extends Types.SignatureGenerator {

        /**
         * An output buffer for type signatures.
         */
        ByteBuffer sigbuf = new ByteBuffer();

        SharedSignatureGenerator(Types types) {
            super(types);
        }

        /**
         * Assemble signature of given type in string buffer.
         * Check for uninitialized types before calling the general case.
         */
        @Override
        public void assembleSig(Type type) {
            switch (type.getTag()) {
                case UNINITIALIZED_THIS:
                case UNINITIALIZED_OBJECT:
                    // we don't yet have a spec for uninitialized types in the
                    // local variable table
                    assembleSig(types.erasure(((UninitializedType)type).qtype));
                    break;
                default:
                    super.assembleSig(type);
            }
        }

        @Override
        protected void append(char ch) {
            sigbuf.appendByte(ch);
        }

        @Override
        protected void append(byte[] ba) {
            sigbuf.appendBytes(ba);
        }

        @Override
        protected void append(Name name) {
            sigbuf.appendName(name);
        }

        @Override
        protected void classReference(ClassSymbol c) {
            enterInner(c);
        }

        protected void reset() {
            sigbuf.reset();
        }

        protected Name toName() {
            return sigbuf.toName(names);
        }
    }

    class WriteablePoolHelper {

        /** Pool entries. */
        private final Map<Object, Integer> keysToPos = new HashMap<>(64);

        final ByteBuffer poolbuf = new ByteBuffer(POOL_BUF_SIZE);

        int currentIndex = 1;

        ArrayDeque<PoolConstant> todo = new ArrayDeque<>();

        String overflowString = null;

        private <P extends PoolConstant> int writeIfNeeded(P p) {
            Object key = p.poolKey(types);
            Integer index = keysToPos.get(key);
            if (index == null) {
                keysToPos.put(key, index = currentIndex++);
                boolean first = todo.isEmpty();
                todo.addLast(p);
                if (first) {
                    while (!todo.isEmpty()) {
                        writeConstant(todo.peekFirst());
                        todo.removeFirst();
                    }
                }
            }
            return index;
        }

        void writeConstant(PoolConstant c) {
            int tag = c.poolTag();
            switch (tag) {
                case ClassFile.CONSTANT_Class: {
                    Type ct = c instanceof ConstantPoolQType ? ((ConstantPoolQType)c).type : (Type)c;
                    Name name = ct.hasTag(ARRAY) ?
                            typeSig(ct) :
                            c instanceof ConstantPoolQType ? names.fromString("Q" + new String(externalize(ct.tsym.flatName())) + ";") : names.fromUtf(externalize(ct.tsym.flatName()));
                    poolbuf.appendByte(tag);
                    poolbuf.appendChar(putName(name));
                    if (ct.hasTag(CLASS)) {
                        enterInner((ClassSymbol)ct.tsym);
                    }
                    if (referencesBeingLinked.contains(c)) {
                        referencesBeingLinked.remove(c);
                    }
                    break;
                }
                case ClassFile.CONSTANT_Utf8: {
                    Name name = (Name)c;
                    poolbuf.appendByte(tag);
                    byte[] bs = name.toUtf();
                    poolbuf.appendChar(bs.length);
                    poolbuf.appendBytes(bs, 0, bs.length);
                    if (overflowString == null && bs.length > MAX_STRING_LENGTH) {
                        //report error only once
                        overflowString = new String(bs);
                    }
                    break;
                }
                case ClassFile.CONSTANT_InterfaceMethodref:
                case ClassFile.CONSTANT_Methodref:
                case ClassFile.CONSTANT_Fieldref: {
                    Symbol sym = (Symbol)c;
                    poolbuf.appendByte(tag);
                    poolbuf.appendChar(putClass((ClassSymbol)sym.owner));
                    poolbuf.appendChar(putNameAndType(sym));
                    if (referencesBeingLinked.contains(c)) {
                        referencesBeingLinked.remove(c);
                    }
                    break;
                }
                case ClassFile.CONSTANT_Package: {
                    PackageSymbol pkg = (PackageSymbol)c;
                    Name pkgName = names.fromUtf(externalize(pkg.flatName()));
                    poolbuf.appendByte(tag);
                    poolbuf.appendChar(putName(pkgName));
                    break;
                }
                case ClassFile.CONSTANT_Module: {
                    ModuleSymbol mod = (ModuleSymbol)c;
                    int modName = putName(mod.name);
                    poolbuf.appendByte(mod.poolTag());
                    poolbuf.appendChar(modName);
                    break;
                }
                case ClassFile.CONSTANT_Integer:
                    poolbuf.appendByte(tag);
                    poolbuf.appendInt((int)((BasicConstant)c).data);
                    break;
                case ClassFile.CONSTANT_Float:
                    poolbuf.appendByte(tag);
                    poolbuf.appendFloat((float)((BasicConstant)c).data);
                    break;
                case ClassFile.CONSTANT_Long:
                    currentIndex++;
                    poolbuf.appendByte(tag);
                    poolbuf.appendLong((long)((BasicConstant)c).data);
                    break;
                case ClassFile.CONSTANT_Double:
                    currentIndex++;
                    poolbuf.appendByte(tag);
                    poolbuf.appendDouble((double)((BasicConstant)c).data);
                    break;
                case ClassFile.CONSTANT_MethodHandle: {
                    MethodHandleSymbol h = (MethodHandleSymbol)c;
                    poolbuf.appendByte(tag);
                    poolbuf.appendByte(h.referenceKind());
                    poolbuf.appendChar(putMember(h.baseSymbol()));
                    break;
                }
                case ClassFile.CONSTANT_MethodType: {
                    Type.MethodType mt = (Type.MethodType)c;
                    poolbuf.appendByte(tag);
                    poolbuf.appendChar(putDescriptor(mt.baseType()));
                    break;
                }
                case ClassFile.CONSTANT_String: {
                    Name utf = names.fromString((String)((BasicConstant)c).data);
                    poolbuf.appendByte(tag);
                    poolbuf.appendChar(putName(utf));
                    break;
                }
                case ClassFile.CONSTANT_NameandType: {
                    NameAndType nt = (NameAndType)c;
                    poolbuf.appendByte(tag);
                    poolbuf.appendChar(putName(nt.name));
                    poolbuf.appendChar(putDescriptor(nt.type));
                    break;
                }
                case ClassFile.CONSTANT_InvokeDynamic: {
                    DynamicMethodSymbol d = (DynamicMethodSymbol)c;
                    poolbuf.appendByte(tag);
                    poolbuf.appendChar(makeBootstrapEntry(d));
                    poolbuf.appendChar(putNameAndType(d));
                    break;
                }
                case ClassFile.CONSTANT_Dynamic: {
                    Symbol.DynamicVarSymbol d = (Symbol.DynamicVarSymbol)c;
                    poolbuf.appendByte(tag);
                    poolbuf.appendChar(makeBootstrapEntry(d));
                    poolbuf.appendChar(putNameAndType(d));
                    break;
                }
                case ClassFile.CONSTANT_Parameter: {
                    Parameter p = (Parameter) c;
                    poolbuf.appendByte(tag);
                    poolbuf.appendByte(p.kind);
                    poolbuf.appendChar(0);
                    break;
                }
                case ClassFile.CONSTANT_Linkage: {
                    Linkage l = (Linkage) c;
                    poolbuf.appendByte(tag);
                    poolbuf.appendChar(putConstant(l.parameter));
                    if (l.hasClassReference()) {
                        poolbuf.appendChar(putClass((Type)l.reference));
                    } else {
                        poolbuf.appendChar(putMember((Symbol)l.reference));
                    }
                    break;
                }
                default:
                    throw new AssertionError("Unexpected constant tag: " + tag);
            }
        }

        void reset() {
            keysToPos.clear();
            currentIndex = 1;
            todo.clear();
            overflowString = null;
            poolbuf.reset();
        }
    }

    int size() {
        return pool.currentIndex;
    }

    /**
     * Return signature of given type
     */
    private Name typeSig(Type type) {
        signatureGen.reset();
        signatureGen.assembleSig(type);
        return signatureGen.toName();
    }

    private Name classSig(Type t) {
        signatureGen.reset();
        List<Type> typarams = t.getTypeArguments();
        if (typarams.nonEmpty()) {
            signatureGen.assembleParamsSig(typarams);
        }
        signatureGen.assembleSig(t.isPrimitiveClass() ? t.referenceProjection() : types.supertype(t));
        if (!t.isPrimitiveClass()) {
            for (Type i : types.interfaces(t))
                signatureGen.assembleSig(i);
        }
        return signatureGen.toName();
    }

    void reset() {
        innerClasses.clear();
        bootstrapMethods.clear();
        pool.reset();
    }
}
