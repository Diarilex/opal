/* License (BSD Style License):
 * Copyright (c) 2009 - 2013
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *  - Neither the name of the Software Technology Group or Technische
 *    Universität Darmstadt nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific
 *    prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package de.tud.cs.st
package bat
package resolved
package analyses

import util.graphs.{ Node, toDot }

import reader.Java7Framework

import java.net.URL
import java.io.File

/**
 * Primary abstraction of a Java project. This class is basically just a container
 * for `ClassFile`s. Additionally, it makes project wide information available such as
 * the class hierarchy.
 *
 * ==Creating Projects==
 * Projects are generally created using factory methods. E.g., the companion object of
 * [[de.tud.cs.st.bat.resolved.analyses.IndexBasedProject]] defines the respective
 * factory method.
 *
 * ==Thread Safety==
 * Implementations of the `ProjektLike` trait need to be thread-safe.
 *
 * @note
 *    This project abstraction does not support (incremenatl) project updates.
 *    Furthermore, it makes use of some global, internal counters. Hence, if you want
 *    to analyze multiple projects in a row, it is highly recommended to analyze the
 *    different projects by associating BAT/each analysis with a different `ClassLoader`.
 *    By using different `ClassLoader`s for the different analyses, the necessary
 *    separation is achieved.
 *
 * @tparam Source The type of the source of the class file. E.g., a `URL`, a `File` object,
 *    a `String` or a Pair `(JarFile,JarEntry)`. This information is needed for, e.g.,
 *    presenting users meaningful messages w.r.t. the location of issues.
 *    We abstract over the type of the resource to facilitate the embedding in existing
 *    tools such as IDEs. E.g., in Eclipse "Resources" are used to identify the
 *    location of a resource (e.g., a source or class file.)
 *
 * @author Michael Eichberg
 */
abstract class ProjectLike[Source] extends (ObjectType ⇒ Option[ClassFile]) {

    ProjectLike.checkForMultipleInstances()

    /**
     * @see `ProjektLike#classFile(ObjectType)`
     */
    final def apply(objectType: ObjectType): Option[ClassFile] = classFile(objectType)

    /**
     * Returns the source (for example, a `File` object or `URL` object) from which
     * the class file was loaded that defines the given object type, if any.
     *
     * @param objectType Some object type. (This method is defined for all `ObjectType`s.)
     */
    def source(objectType: ObjectType): Option[Source]

    /**
     * Returns true if the given class file belongs to the library part of the project.
     */
    def isLibraryType(classFile: ClassFile): Boolean

    /**
     * Returns true if the given type file belongs to the library part of the project.
     * This is generally the case if no class file was loaded for the given type.
     */
    def isLibraryType(objectType: ObjectType): Boolean

    /**
     * Returns the class file that defines the given `objectType`; if any.
     *
     * @param objectType Some object type. (This method is defined for all `ObjectType`s.)
     */
    def classFile(objectType: ObjectType): Option[ClassFile]

    /**
     * Returns the given method's class file. This method is only defined if
     * the method was previously added to this project. (I.e., the class file which
     * defines the method was added.)
     */
    def classFile(method: Method): ClassFile

    /**
     * Returns the given field's class file. This method is only defined if
     * the field was previously added to this project. (I.e., the class file which
     * defines the field was added.)
     */
    def classFile(field: Field): ClassFile

    /**
     * Calls the given method for class file of this project.
     *
     * The class files are traversed in no defined order.
     */
    def foreachClassFile[U](f: ClassFile ⇒ U): Unit

    /**
     * Returns `true` if all class files satisfy the specified predicate.
     *
     * The class files are traversed in no defined order.
     *
     * The evaluation is immediately aborted when a class file does not satisfy the predicate
     * (short-cut evaluation).
     */
    def forallClassFiles[U](f: ClassFile ⇒ Boolean): Boolean

    /**
     * Calls the given method for each method of this project.
     *
     * The methods are traversed in no defined order.
     */
    def foreachMethod[U](f: Method ⇒ U): Unit

    /**
     * Returns `true` if all methods satisfy the specified predicate.
     *
     * The methods are traversed in no defined order.
     *
     * The evaluation is aborted when a method does not satisfy the predicate
     * (short-cut evaluation).
     */
    def forallMethods[U](f: Method ⇒ Boolean): Boolean

    /**
     * Returns the method with the given id.
     *
     * @note In general, this method should only be used internally by an analysis'
     *    implementation.
     *    No analysis should ever expose (`Int`) ids in their interface.
     *
     * @param methodID The unique id of a method that was (explicitly) added to this
     *    project.
     */
    def method(methodID: Int): Method

    /**
     * Returns the class file that defines the object type with the given id.
     *
     * @note In general, this method should only be used internally by an analysis'
     *    implementation.
     *    No analysis should ever expose (`Int`) ids in their interface.
     *
     * @param objectTypeID The unique id of an object type.
     *    This method is only defined if the class file that defines the given
     *    object type was added to this project.
     */
    def classFile(objectTypeID: Int): ClassFile

    /**
     * The number of different object types that were seen up to the point when the
     * project was created.
     *
     * @note This number does not change, if an analysis later on creates `ObjectType`
     *    instances for object types that are not defined as part of the project. To
     *    get the ''current number'' of different `ObjectType`s use the corresponding
     *    method of the class [[de.tud.cs.st.bat.resolved.ObjectType]].
     */
    final val objectTypesCount = ObjectType.objectTypesCount

    /**
     * The number of methods that have been loaded since the start of BAT.
     * This is equivalent to the number of methods of this project unless other
     * projects were created simultaneously or before this project.
     */
    final val methodsCount = Method.methodsCount

    /**
     * The number of fields that have been loaded since the start of BAT.
     * This is equivalent to the number of fields of this project unless other
     * projects were created simultaneously or before this project.
     */
    final val fieldCount = Field.fieldsCount

    /**
     * This project's class files.
     */
    def classFiles: Iterable[ClassFile]

    /**
     * Converts this project abstraction into a standard Java `HashMap`.
     *
     * @note This method should only be used by Java projects that want to interact
     *      with BAT.
     */
    def toJavaMap(): java.util.HashMap[ObjectType, ClassFile] = {
        val map = new java.util.HashMap[ObjectType, ClassFile]
        for (classFile ← classFiles) map.put(classFile.thisType, classFile)
        map
    }

    /**
     * This project's class hierarchy.
     */
    val classHierarchy: ClassHierarchy

    /**
     * Returns all available `ClassFile` objects for the given `objectTypes` that
     * pass the given `filter`. `ObjectType`s for which no `ClassFile` is available
     * are ignored.
     */
    def lookupClassFiles(
        objectTypes: Traversable[ObjectType])(
            filter: ClassFile ⇒ Boolean): Traversable[ClassFile] =
        (
            objectTypes.view.map(apply(_)) filter { someClassFile: Option[ClassFile] ⇒
                someClassFile.isDefined && filter(someClassFile.get)
            }
        ).map(_.get)
}

private object ProjectLike {

    val projectCount = new java.util.concurrent.atomic.AtomicInteger(0)

    def checkForMultipleInstances(): Unit = {
        if (projectCount.incrementAndGet() > 1) {
            val err = Console.err
            import err.{ println, print }
            import Console._
            print(BOLD + MAGENTA)
            print("Creating multiple project instances is not recommended. ")
            println("See the documentation of: ")
            println("\t"+classOf[ProjectLike[_]].getName())
            println("for further details.")
            print(RESET)
        }
    }

    /**
     * Given a reference to a class file, jar file or a folder containing jar and class
     * files, all class files will be loaded and a project will be returned.
     */
    def createProject(file: File): ProjectLike[URL] = {
        IndexBasedProject[URL](Java7Framework.ClassFiles(file))
    }
}

