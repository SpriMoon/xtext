/*
* generated by Xtext
*/
package org.eclipse.xtext.example.fowlerdsl.ui.labeling

import com.google.inject.Inject
import org.eclipse.xtext.example.fowlerdsl.statemachine.State

/**
 * Provides labels for a EObjects.
 * 
 * see http://www.eclipse.org/Xtext/documentation.html#labelProvider
 */
class StatemachineLabelProvider extends org.eclipse.xtext.ui.label.DefaultEObjectLabelProvider {

	@Inject
	new(org.eclipse.emf.edit.ui.provider.AdapterFactoryLabelProvider delegate) {
		super(delegate);
	}

	def text(State state) {
		'state ' + state.name
	}

//	def image(State ele) {
//		'State.gif'
//	}
}