/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.psi.psiUtil

import com.intellij.psi.AbstractElementManipulator
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.psi.KtPsiFactory
import com.intellij.openapi.util.text.StringUtil

public class StringTemplateExpressionManipulator: AbstractElementManipulator<KtStringTemplateExpression>() {
    override fun handleContentChange(element: KtStringTemplateExpression, range: TextRange, newContent: String): KtStringTemplateExpression? {
        val node = element.getNode()
        val content = if (node.getFirstChildNode().getTextLength() == 1) StringUtil.escapeStringCharacters(newContent) else newContent
        val oldText = node.getText()
        val newText = oldText.substring(0, range.getStartOffset()) + content + oldText.substring(range.getEndOffset())
        val expression = KtPsiFactory(element.getProject()).createExpression(newText)
        node.replaceAllChildrenToChildrenOf(expression.getNode())
        return node.getPsi(javaClass())
    }

    override fun getRangeInElement(element: KtStringTemplateExpression): TextRange {
        return element.getContentRange()
    }
}
