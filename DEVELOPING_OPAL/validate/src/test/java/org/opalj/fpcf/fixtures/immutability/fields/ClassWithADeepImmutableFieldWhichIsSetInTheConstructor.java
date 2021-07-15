/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.fpcf.fixtures.immutability.fields;

import org.opalj.br.fpcf.analyses.L0ClassImmutabilityAnalysis;
import org.opalj.br.fpcf.analyses.L0FieldAssignabilityAnalysis;
import org.opalj.br.fpcf.analyses.L0TypeImmutabilityAnalysis;
import org.opalj.fpcf.properties.immutability.classes.TransitivelyImmutableClass;
import org.opalj.fpcf.properties.immutability.fields.TransitivelyImmutableField;
import org.opalj.fpcf.properties.immutability.fields.NonTransitivelyImmutableField;
import org.opalj.fpcf.properties.immutability.field_assignability.EffectivelyNonAssignableField;
import org.opalj.fpcf.properties.immutability.types.MutableType;
import org.opalj.tac.fpcf.analyses.L1FieldAssignabilityAnalysis;
import org.opalj.tac.fpcf.analyses.L2FieldAssignabilityAnalysis;
import org.opalj.tac.fpcf.analyses.immutability.L1ClassImmutabilityAnalysis;
import org.opalj.tac.fpcf.analyses.immutability.L1TypeImmutabilityAnalysis;
import org.opalj.tac.fpcf.analyses.immutability.L0FieldImmutabilityAnalysis;
import org.opalj.tac.fpcf.analyses.immutability.fieldassignability.L3FieldAssignabilityAnalysis;

@MutableType(value = "Class is extensible", analyses = {L0TypeImmutabilityAnalysis.class,
        L1TypeImmutabilityAnalysis.class})
@TransitivelyImmutableClass(value = "class has only the deep immutable field fec",
        analyses = {L1ClassImmutabilityAnalysis.class, L0ClassImmutabilityAnalysis.class})
public class ClassWithADeepImmutableFieldWhichIsSetInTheConstructor {

    @NonTransitivelyImmutableField(value = "can not handle transitive immutability",
            analyses  = {L0FieldAssignabilityAnalysis.class, L1FieldAssignabilityAnalysis.class,
                    L2FieldAssignabilityAnalysis.class})
    @TransitivelyImmutableField(value = "immutable reference and deep immutable field type",
            analyses = L0FieldImmutabilityAnalysis.class)
    @EffectivelyNonAssignableField(value = "declared final field reference",
            analyses = L3FieldAssignabilityAnalysis.class)
    private final FinalEmptyClass fec;

    public ClassWithADeepImmutableFieldWhichIsSetInTheConstructor(FinalEmptyClass fec) {
        this.fec = fec;
    }
}