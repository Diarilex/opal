/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
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
package org.opalj
package da

import scala.xml.Node

import org.opalj.bi.AccessFlagsContexts.METHOD_PARAMETERS

/**
 * @author Michael Eichberg
 * @author Wael Alkhatib
 * @author Isbel Isbel
 * @author Noorulla Sharief
 */
case class MethodParameters_attribute(
        attribute_name_index: Constant_Pool_Index,
        parameters:           IndexedSeq[MethodParameter]
) extends Attribute {

    final override def attribute_length = 1 /*parameters_count*/ + parameters.size * 4

    override def toXHTML(implicit cp: Constant_Pool): Node = {
        <span>{ cp(attribute_name_index).toString(cp) }({ parametersToXHTML(cp) })</span>
    }

    def parametersToXHTML(implicit cp: Constant_Pool) = parameters.map(_.toXHTML(cp))

}

case class MethodParameter(
        name_index:   Constant_Pool_Index,
        access_flags: Int
) {

    def toXHTML(implicit cp: Constant_Pool): Seq[Node] = {
        val (accessFlags, _) = accessFlagsToXHTML(access_flags, METHOD_PARAMETERS)
        val parameterName =
            if (name_index == 0)
                "<Formal Parameter>"
            else
                cp(name_index).toString(cp)
        List(accessFlags, <span>{ parameterName }</span>)
    }
}
