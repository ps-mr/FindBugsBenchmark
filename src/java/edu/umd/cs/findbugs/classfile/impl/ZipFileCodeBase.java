/*
 * FindBugs - Find Bugs in Java programs
 * Copyright (C) 2006, University of Maryland
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.classfile.impl;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import edu.umd.cs.findbugs.classfile.ICodeBaseEntry;
import edu.umd.cs.findbugs.classfile.ICodeBaseIterator;
import edu.umd.cs.findbugs.classfile.ICodeBaseLocator;

/**
 * Implementation of ICodeBase to read from a zip file or jar file.
 *
 * @author David Hovemeyer
 */
public class ZipFileCodeBase extends AbstractScannableCodeBase {
    ZipFile zipFile;

    /**
     * Constructor.
     *
     * @param codeBaseLocator
     *            the codebase locator for this codebase
     * @param file
     *            the File containing the zip file (may be a temp file if the
     *            codebase was copied from a nested zipfile in another codebase)
     */
    public ZipFileCodeBase(ICodeBaseLocator codeBaseLocator, File file) throws IOException {
        super(codeBaseLocator);
        try {
            this.zipFile = new ZipFile(file);
            setLastModifiedTime(file.lastModified());
        } catch (ZipException e) {
            File parent = file.getParentFile();
            if (!file.exists()) {
                if (!(parent.exists() && parent.isDirectory() && parent.canRead()))
                    throw new ZipException("Can't read directory containing zip file: " + file);
                throw new ZipException("Zip file doesn't exist: " + file);
            }
            if (!file.canRead())
                throw new ZipException("Can't read file zip file: " + file);
            if (!file.isFile())
                throw new ZipException("Zip file isn't a normal file: " + file);
            if (file.length() == 0)
                throw new ZipException("Zip file is empty: " + file);
            DataInputStream in = new DataInputStream(new FileInputStream(file));
            ZipException e2 = new ZipException("Error opening zip file " + file + " of " + file.length() + " bytes");
            int magicBytes;
            try {
                magicBytes = in.readInt();
            } catch (IOException e3) {
                throw new ZipException(String.format("Unable read first 4 bytes of zip file %s of %d bytes", file, file.length()));
            }
            if (magicBytes != 0x504b0304)
                throw new ZipException(String.format("Wrong magic bytes of %x for zip file %s of %d bytes", magicBytes, file,
                        file.length()));
            e2.initCause(e);
            throw e2;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * edu.umd.cs.findbugs.classfile.ICodeBase#lookupResource(java.lang.String)
     */
    public ICodeBaseEntry lookupResource(String resourceName) {
        // Translate resource name, in case a resource name
        // has been overridden and the resource is being accessed
        // using the overridden name.
        resourceName = translateResourceName(resourceName);

        try {
            ZipEntry entry = zipFile.getEntry(resourceName);
            if (entry == null) {
                return null;
            }
            return new ZipFileCodeBaseEntry(this, entry);
        } catch (IllegalStateException ise) {
            // zipFile.getEntry() throws IllegalStateException if the zip file
            // has been closed
            return null;
        }
    }

    public ICodeBaseIterator iterator() {
        final Enumeration<? extends ZipEntry> zipEntryEnumerator = zipFile.entries();

        return new ICodeBaseIterator() {
            ZipFileCodeBaseEntry nextEntry;

            public boolean hasNext() {
                scanForNextEntry();
                return nextEntry != null;
            }

            /*
             * (non-Javadoc)
             *
             * @see edu.umd.cs.findbugs.classfile.ICodeBaseIterator#next()
             */
            public ICodeBaseEntry next() throws InterruptedException {
                scanForNextEntry();
                if (nextEntry == null) {
                    throw new NoSuchElementException();
                }
                ICodeBaseEntry result = nextEntry;
                nextEntry = null;
                return result;
            }

            private void scanForNextEntry() {
                while (nextEntry == null) {
                    if (!zipEntryEnumerator.hasMoreElements()) {
                        return;
                    }

                    ZipEntry zipEntry = zipEntryEnumerator.nextElement();

                    if (!zipEntry.isDirectory()) {
                        addLastModifiedTime(zipEntry.getTime());
                        nextEntry = new ZipFileCodeBaseEntry(ZipFileCodeBase.this, zipEntry);
                        break;
                    }
                }
            }

        };
    }

    /*
     * (non-Javadoc)
     *
     * @see edu.umd.cs.findbugs.classfile.ICodeBase#getPathName()
     */
    public String getPathName() {
        return zipFile.getName();
    }

    /*
     * (non-Javadoc)
     *
     * @see edu.umd.cs.findbugs.classfile.ICodeBase#close()
     */
    public void close() {
        try {
            zipFile.close();
        } catch (IOException e) {
            // Ignore
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return zipFile.getName();
    }
}
