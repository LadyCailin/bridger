/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.bridger;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class Bridger implements ClassFileTransformer {
    private final AtomicInteger transformedMethodCount = new AtomicInteger();
    private final AtomicInteger transformedMethodCallCount = new AtomicInteger();

    public Bridger() {
    }

    /**
     * Translate all {@code .class} files in the given list of files and directories.
     *
     * @param args the file and directory names
     */
    public static void main(String[] args) {
        final Bridger bridger = new Bridger();
        if(args.length > 0 && "--single-transform".equals(args[0])) {
            if(args.length != 3 && args.length != 4) {
                System.err.println("Usage: --single-transform path/of/class/file deprecatedMethodName [methodDescriptor]");
                return;
            }
            File classFile = new File(args[1]);
            if(!classFile.exists()) {
                System.err.println("Class file does not exist.");
                return;
            }
            bridger.transformSingle(classFile, args[2], args.length >= 4 ? args[3] : null);
        } else {
            bridger.transformRecursive(args);
        }
        System.out.printf("Translated %d methods and %d method calls%n", bridger.getTransformedMethodCount(), bridger.getTransformedMethodCallCount());
    }

    /**
     * Translate all {@code .class} files in the given list of files and directories.
     *
     * @param names the file and directory names
     */
    public void transformRecursive(String... names) {
        final File[] files = new File[names.length];
        for (int i = 0; i < names.length; i++) {
            files[i] = new File(names[i]);
        }
        transformRecursive(files);
    }

    /**
     * Translate all {@code .class} files in the given list of files and directories.
     *
     * @param files the files and directories
     */
    public void transformRecursive(File... files) {
        for (File file : files) {
            if (file.isDirectory()) {
                transformRecursive(file.listFiles());
            } else if (file.getName().endsWith(".class")) {
                try {
                    transform(new RandomAccessFile(file, "rw"));
                } catch (Exception e) {
                    System.out.println("Failed to transform " + file + ": " + e);
                }
            }
            // else ignore
        }
    }

    public void transformSingle(File classFile, String methodName, String methodDescriptor) {
        try {
            transformSingle(new RandomAccessFile(classFile, "rw"), methodName, methodDescriptor);
        } catch (Exception e) {
            System.out.println("Failed to transform " + classFile + ": " + e);
        }
    }

    public byte[] transform(final ClassLoader loader, final String className, final Class<?> classBeingRedefined, final ProtectionDomain protectionDomain, final byte[] classfileBuffer) throws IllegalClassFormatException {
        ClassWriter classWriter = new ClassWriter(0);
        final ClassReader classReader = new ClassReader(classfileBuffer);
        doAccept(classWriter, classReader);
        return classWriter.toByteArray();
    }

    public byte[] transform(final InputStream input) throws IllegalClassFormatException, IOException {
        ClassWriter classWriter = new ClassWriter(0);
        final ClassReader classReader = new ClassReader(input);
        doAccept(classWriter, classReader);
        return classWriter.toByteArray();
    }

    public byte[] transformSingle(final InputStream input, String methodName, String methodDescriptor)
            throws IllegalClassFormatException, IOException {
        ClassWriter classWriter = new ClassWriter(0);
        final ClassReader classReader = new ClassReader(input);
        doAcceptSingle(classWriter, classReader, methodName, methodDescriptor);
        return classWriter.toByteArray();
    }

    public int getTransformedMethodCount() {
        return transformedMethodCount.get();
    }

    public int getTransformedMethodCallCount() {
        return transformedMethodCallCount.get();
    }

    private void doAccept(final ClassWriter classWriter, final ClassReader classReader) throws IllegalClassFormatException {
        try {
            classReader.accept(new TranslatingClassVisitor(classWriter), 0);
        } catch (RuntimeException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IllegalClassFormatException) {
                throw (IllegalClassFormatException) cause;
            }
            throw e;
        }
    }

     private void doAcceptSingle(final ClassWriter classWriter, final ClassReader classReader, String methodName,
             String methodDescriptor) throws IllegalClassFormatException {
        try {
            classReader.accept(new TranslatingSingleMethodClassVisitor(classWriter, methodName, methodDescriptor), 0);
        } catch (RuntimeException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IllegalClassFormatException) {
                throw (IllegalClassFormatException) cause;
            }
            throw e;
        }
    }

    public void transform(final InputStream input, final OutputStream output) throws IllegalClassFormatException, IOException {
        output.write(transform(input));
    }

    public void transform(final RandomAccessFile file) throws IllegalClassFormatException, IOException {
        try {
            file.seek(0);
            try (InputStream is = new FileInputStream(file.getFD())) {
                final byte[] bytes = transform(is);
                file.seek(0);
                file.write(bytes);
                file.setLength(bytes.length);
            }
            file.close();
        } finally {
            safeClose(file);
        }
    }

    public void transformSingle(final RandomAccessFile file, String methodName, String methodDescriptor)
            throws IllegalClassFormatException, IOException {
        try {
            file.seek(0);
            try (InputStream is = new FileInputStream(file.getFD())) {
                final byte[] bytes = transformSingle(is, methodName, methodDescriptor);
                file.seek(0);
                file.write(bytes);
                file.setLength(bytes.length);
            }
            file.close();
        } finally {
            safeClose(file);
        }
    }

    private static void safeClose(final Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (Throwable ignored) {}
    }

    private class TranslatingClassVisitor extends ClassVisitor {

        public TranslatingClassVisitor(final ClassWriter classWriter) {
            super(Opcodes.ASM9, classWriter);
        }

        public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
            boolean removeExistingBridgeMethods = Boolean.parseBoolean(System.getProperty("jboss.bridger.remove_existing_bridge_methods", "false"));

            final MethodVisitor defaultVisitor;
            final int idx = name.indexOf("$$bridge");
            if (idx != -1) {
                transformedMethodCount.getAndIncrement();
                defaultVisitor = super.visitMethod(access | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC, name.substring(0, idx), desc, signature, exceptions);
            } else {
                if (removeExistingBridgeMethods && (access & Opcodes.ACC_BRIDGE) != 0) {
                    return null;
                }
                defaultVisitor = super.visitMethod(access, name, desc, signature, exceptions);
            }
            return new MethodVisitor(Opcodes.ASM9, defaultVisitor) {
                public void visitInvokeDynamicInsn(final String name, final String desc, final Handle bsm, final Object... bsmArgs) {
                    final int idx = name.indexOf("$$bridge");
                    if (idx != -1) {
                        transformedMethodCallCount.getAndIncrement();
                        final String realName = name.substring(0, idx);
                        super.visitInvokeDynamicInsn(realName, desc, bsm, bsmArgs);
                    } else {
                        super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
                    }
                }

                public void visitMethodInsn(final int opcode, final String owner, final String name, final String desc, final boolean itf) {
                    final int idx = name.indexOf("$$bridge");
                    if (idx != -1) {
                        transformedMethodCallCount.getAndIncrement();
                        final String realName = name.substring(0, idx);
                        super.visitMethodInsn(opcode, owner, realName, desc, itf);
                    } else {
                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                    }
                }
            };
        }
    }

    private class TranslatingSingleMethodClassVisitor extends ClassVisitor {

        private final String methodName;
        private final String methodDescriptor;

        public TranslatingSingleMethodClassVisitor(final ClassWriter classWriter, String methodName, String methodDescriptor) {
            super(Opcodes.ASM9, classWriter);
            this.methodName = methodName;
            this.methodDescriptor = methodDescriptor;
        }

        public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
            boolean removeExistingBridgeMethods = Boolean.parseBoolean(System.getProperty("jboss.bridger.remove_existing_bridge_methods", "false"));

            final MethodVisitor defaultVisitor;
            final String methodName = this.methodName;
            final String methodDescriptor = this.methodDescriptor;

            if (name.equals(methodName) && (methodDescriptor == null || desc.equals(methodDescriptor))) {
                transformedMethodCount.getAndIncrement();
                defaultVisitor = super.visitMethod(access | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC, name, desc, signature, exceptions);
            } else {
                if (removeExistingBridgeMethods && (access & Opcodes.ACC_BRIDGE) != 0) {
                    return null;
                }
                defaultVisitor = super.visitMethod(access, name, desc, signature, exceptions);
            }
            return new MethodVisitor(Opcodes.ASM9, defaultVisitor) {
                public void visitInvokeDynamicInsn(final String name, final String desc, final Handle bsm, final Object... bsmArgs) {
                    if (name.equals(methodName) && (methodDescriptor == null || desc.equals(methodDescriptor))) {
                        transformedMethodCallCount.getAndIncrement();
                    }
                    super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
                }

                public void visitMethodInsn(final int opcode, final String owner, final String name, final String desc, final boolean itf) {
                    if (name.equals(methodName) && (methodDescriptor == null || desc.equals(methodDescriptor))) {
                        transformedMethodCallCount.getAndIncrement();
                    }
                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                }
            };
        }
    }
}
