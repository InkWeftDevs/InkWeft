// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** Editable, local mathematical notation. No TeX file access or user-defined macros. */
object FormulaText {
    fun validate(text:String) {
        require(text.isNotBlank()&&text.length<=4000){"FORMULA_LENGTH"}
        require(text.count{it=='\n'}<32&&text.none{it.code<32&&it!='\n'&&it!='\t'}){"FORMULA_CONTROL"}
        require(!Regex("\\\\(?:includegraphics|input|include|openin|openout|read|write|immediate|special|newcommand|renewcommand|providecommand|newenvironment|renewenvironment|def|gdef|edef|xdef|csname|loop|repeat|usepackage|documentclass|jlmExternalFont)\\b").containsMatchIn(text)){"FORMULA_COMMAND"}
        var depth=0;var escaped=false
        for(c in text){
            if(escaped){escaped=false;continue}
            if(c=='\\'){escaped=true;continue}
            if(c=='{'){depth++;require(depth<=32){"FORMULA_DEPTH"}}
            if(c=='}'){depth--;require(depth>=0){"FORMULA_BRACES"}}
        }
        require(depth==0){"FORMULA_BRACES"}
    }
}
