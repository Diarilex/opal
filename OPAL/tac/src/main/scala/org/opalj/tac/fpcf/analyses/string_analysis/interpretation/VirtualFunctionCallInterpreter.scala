/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.tac.fpcf.analyses.string_analysis.interpretation

import org.opalj.br.cfg.CFG
import org.opalj.br.ComputationalTypeFloat
import org.opalj.br.ComputationalTypeInt
import org.opalj.br.ObjectType
import org.opalj.br.fpcf.properties.StringConstancyProperty
import org.opalj.br.fpcf.properties.string_definition.StringConstancyInformation
import org.opalj.br.fpcf.properties.string_definition.StringConstancyLevel
import org.opalj.br.fpcf.properties.string_definition.StringConstancyType
import org.opalj.tac.Stmt
import org.opalj.tac.TACStmts
import org.opalj.tac.VirtualFunctionCall
import org.opalj.tac.fpcf.analyses.string_analysis.V

/**
 * The `VirtualFunctionCallInterpreter` is responsible for processing [[VirtualFunctionCall]]s.
 * The list of currently supported function calls can be seen in the documentation of
 * [[interpret]].
 *
 * @see [[AbstractStringInterpreter]]
 *
 * @author Patrick Mell
 */
class VirtualFunctionCallInterpreter(
        cfg:         CFG[Stmt[V], TACStmts[V]],
        exprHandler: InterpretationHandler
) extends AbstractStringInterpreter(cfg, exprHandler) {

    override type T = VirtualFunctionCall[V]

    /**
     * Currently, this implementation supports the interpretation of the following function calls:
     * <ul>
     * <li>`append`: Calls to the `append` function of [[StringBuilder]] and [[StringBuffer]].</li>
     * <li>
     *     `toString`: Calls to the `append` function of [[StringBuilder]] and [[StringBuffer]]. As
     *     a `toString` call does not change the state of such an object, an empty list will be
     *     returned.
     * </li>
     * <li>
     *     `replace`: Calls to the `replace` function of [[StringBuilder]] and [[StringBuffer]]. For
     *     further information how this operation is processed, see
     *     [[VirtualFunctionCallInterpreter.interpretReplaceCall]].
     * </li>
     * <li>
     *     Apart from these supported methods, a list with [[StringConstancyProperty.lowerBound]]
     *     will be returned in case the passed method returns a [[java.lang.String]].
     * </li>
     * </ul>
     *
     * If none of the above-described cases match, an empty list will be returned.
     *
     * @see [[AbstractStringInterpreter.interpret]]
     */
    override def interpret(instr: T): List[StringConstancyInformation] = {
        instr.name match {
            case "append"   ⇒ interpretAppendCall(instr).getOrElse(List())
            case "toString" ⇒ interpretToStringCall(instr)
            case "replace"  ⇒ interpretReplaceCall(instr)
            case _ ⇒
                instr.descriptor.returnType match {
                    case obj: ObjectType if obj.fqn == "java/lang/String" ⇒
                        List(StringConstancyProperty.lowerBound.stringConstancyInformation)
                    case _ ⇒ List()
                }
        }
    }

    /**
     * Function for processing calls to [[StringBuilder#append]] or [[StringBuffer#append]]. Note
     * that this function assumes that the given `appendCall` is such a function call! Otherwise,
     * the expected behavior cannot be guaranteed.
     */
    private def interpretAppendCall(
        appendCall: VirtualFunctionCall[V]
    ): Option[List[StringConstancyInformation]] = {
        val receiverValues = receiverValuesOfAppendCall(appendCall)
        val appendValue = valueOfAppendCall(appendCall)

        // The case can occur that receiver and append value are empty; although, it is
        // counter-intuitive, this case may occur if both, the receiver and the parameter, have been
        // processed before
        if (receiverValues.isEmpty && appendValue.isEmpty) {
            None
        } // It might be that we have to go back as much as to a New expression. As they do not
        // produce a result (= empty list), the if part
        else if (receiverValues.isEmpty) {
            Some(List(appendValue.get))
        } // The append value might be empty, if the site has already been processed (then this
        // information will come from another StringConstancyInformation object
        else if (appendValue.isEmpty) {
            Some(receiverValues)
        } // Receiver and parameter information are available => Combine them
        else {
            Some(receiverValues.map { nextSci ⇒
                StringConstancyInformation(
                    StringConstancyLevel.determineForConcat(
                        nextSci.constancyLevel, appendValue.get.constancyLevel
                    ),
                    StringConstancyType.APPEND,
                    nextSci.possibleStrings + appendValue.get.possibleStrings
                )
            })
        }
    }

    /**
     * This function determines the current value of the receiver object of an `append` call.
     */
    private def receiverValuesOfAppendCall(
        call: VirtualFunctionCall[V]
    ): List[StringConstancyInformation] =
        // There might be several receivers, thus the map; from the processed sites, however, use
        // only the head as a single receiver interpretation will produce one element
        call.receiver.asVar.definedBy.toArray.sorted.map(
            exprHandler.processDefSite
        ).filter(_.nonEmpty).map(_.head).toList

    /**
     * Determines the (string) value that was passed to a `String{Builder, Buffer}#append` method.
     * This function can process string constants as well as function calls as argument to append.
     */
    private def valueOfAppendCall(
        call: VirtualFunctionCall[V]
    ): Option[StringConstancyInformation] = {
        val param = call.params.head.asVar
        // .head because we want to evaluate only the first argument of append
        val defSiteParamHead = param.definedBy.head
        var value = exprHandler.processDefSite(defSiteParamHead)
        // If defSiteParamHead points to a New, value will be the empty list. In that case, process
        // the first use site (which is the <init> call)
        if (value.isEmpty) {
            value = exprHandler.processDefSite(
                cfg.code.instructions(defSiteParamHead).asAssignment.targetVar.usedBy.toArray.min
            )
        }
        param.value.computationalType match {
            // For some types, we know the (dynamic) values
            case ComputationalTypeInt ⇒
                // The value was already computed above; however, we need to check whether the
                // append takes an int value or a char (if it is a constant char, convert it)
                if (call.descriptor.parameterType(0).isCharType &&
                    value.head.constancyLevel == StringConstancyLevel.CONSTANT) {
                    Some(value.head.copy(
                        possibleStrings = value.head.possibleStrings.toInt.toChar.toString
                    ))
                } else {
                    Some(value.head)
                }
            case ComputationalTypeFloat ⇒
                Some(InterpretationHandler.getConstancyInformationForDynamicFloat)
            // Otherwise, try to compute
            case _ ⇒
                // It might be necessary to merge the values of the receiver and of the parameter
                value.size match {
                    case 0 ⇒ None
                    case 1 ⇒ Some(value.head)
                    case _ ⇒ Some(StringConstancyInformation(
                        StringConstancyLevel.determineForConcat(
                            value.head.constancyLevel, value(1).constancyLevel
                        ),
                        StringConstancyType.APPEND,
                        value.head.possibleStrings + value(1).possibleStrings
                    ))
                }
        }
    }

    /**
     * Function for processing calls to [[StringBuilder#toString]] or [[StringBuffer#toString]].
     * Note that this function assumes that the given `toString` is such a function call! Otherwise,
     * the expected behavior cannot be guaranteed.
     */
    private def interpretToStringCall(
        call: VirtualFunctionCall[V]
    ): List[StringConstancyInformation] =
        exprHandler.processDefSite(call.receiver.asVar.definedBy.head)

    /**
     * Function for processing calls to [[StringBuilder#replace]] or [[StringBuffer#replace]].
     * Currently, this function simply approximates `replace` functions by returning a list with one
     * element - the element currently is provided by
     * [[InterpretationHandler.getStringConstancyInformationForReplace]].
     */
    private def interpretReplaceCall(
        instr: VirtualFunctionCall[V]
    ): List[StringConstancyInformation] =
        List(InterpretationHandler.getStringConstancyInformationForReplace)

}