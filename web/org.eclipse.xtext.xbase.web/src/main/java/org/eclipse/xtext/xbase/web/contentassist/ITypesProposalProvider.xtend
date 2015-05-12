/*******************************************************************************
 * Copyright (c) 2015 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.xtext.xbase.web.contentassist

import org.eclipse.emf.ecore.EReference
import org.eclipse.xtext.common.types.JvmType
import org.eclipse.xtext.conversion.IValueConverter
import org.eclipse.xtext.ide.editor.contentassist.ContentAssistContext
import org.eclipse.xtext.util.IAcceptor
import org.eclipse.xtext.web.server.contentassist.ContentAssistResult

interface ITypesProposalProvider {
	
	def void createTypeProposals(ContentAssistContext context, EReference typeReference,
		IAcceptor<ContentAssistResult.Entry> acceptor)
	 
	def void createTypeProposals(ContentAssistContext context, EReference typeReference, Filter filter,
		IAcceptor<ContentAssistResult.Entry> acceptor)
	 
	def void createTypeProposals(ContentAssistContext context, EReference typeReference, Filter filter,
		IValueConverter<String> valueConverter, IAcceptor<ContentAssistResult.Entry> acceptor)
	 
	def void createSubTypeProposals(JvmType superType, ContentAssistContext context, EReference typeReference,
		IAcceptor<ContentAssistResult.Entry> acceptor)
	 
	def void createSubTypeProposals(JvmType superType, ContentAssistContext context, EReference typeReference,
		Filter filter, IAcceptor<ContentAssistResult.Entry> acceptor)
	 
	def void createSubTypeProposals(JvmType superType, ContentAssistContext context, EReference typeReference,
		Filter filter, IValueConverter<String> valueConverter, IAcceptor<ContentAssistResult.Entry> acceptor)
	 
	interface Filter {
		def boolean accept(int modifiers, char[] packageName, char[] simpleTypeName, char[][] enclosingTypeNames, String path)
	}
	
}