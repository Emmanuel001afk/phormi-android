import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const script = fs.readFileSync(new URL('../../app/src/main/assets/phormi_assistant.js', import.meta.url), 'utf8');
function page() {
  const document = {title: 'Test page', readyState: 'complete', body: {innerText: 'Visible page text'}, activeElement: null, elements: []};
  class Element {
    constructor(tag, label = '', type = 'text') {
      this.tagName = tag; this.innerText = label; this.type = type;
      this.isConnected = true; this.hidden = false; this.disabled = false; this.readOnly = false;
      this.isContentEditable = false; this.attributes = {}; this.events = []; this.clicks = 0;
    }
    getAttribute(name) { return this.attributes[name] || null; }
    getClientRects() { return [{}]; }
    focus() { document.activeElement = this; }
    scrollIntoView() {}
    click() { this.clicks++; }
    dispatchEvent(event) { this.events.push(event); return true; }
  }
  class Input extends Element {
    constructor(label, type = 'text') { super('INPUT', label, type); this._value = ''; this.setterCalls = 0; }
    set value(value) { this.setterCalls++; this._value = value; }
    get value() { return this._value; }
  }
  class Textarea extends Input { constructor(label) { super(label); this.tagName = 'TEXTAREA'; } }
  // The adapter intentionally uses the textarea prototype's own native setter.
  Object.defineProperty(Textarea.prototype, 'value', Object.getOwnPropertyDescriptor(Input.prototype, 'value'));
  class Event { constructor(type, options) { this.type = type; Object.assign(this, options); } }
  document.querySelectorAll = () => document.elements;
  document.createRange = () => ({selectNodeContents() {}});
  document.execCommand = () => false;
  const window = {getSelection: () => ({removeAllRanges() {}, addRange() {}})};
  vm.runInNewContext(script, {window, document, location: {href: 'https://example.test'},
    getComputedStyle: () => ({display: 'block', visibility: 'visible'}),
    HTMLInputElement: Input, HTMLTextAreaElement: Textarea, Event, InputEvent: Event, KeyboardEvent: Event});
  return {document, window, Element, Input, Textarea, adapter: window.__phormiAssistant};
}

test('snapshot omits passwords, OTPs, payment fields and input values', () => {
  const p = page();
  const password = new p.Input('Password', 'password'); password.value = 'never send this';
  const otp = new p.Input('Verification code'); const payment = new p.Input('Card number');
  const editor = new p.Input('Search'); editor.value = 'private draft value';
  p.document.elements = [password, otp, payment, editor];
  const snap = p.adapter.snapshot();
  assert.equal(snap.controls.length, 1);
  assert.equal(snap.controls[0].label, 'Search');
  assert.ok(!JSON.stringify(snap).includes('private draft value'));
  assert.ok(!JSON.stringify(snap).includes('never send this'));
});

test('typing uses the exact control ID and native input setter', () => {
  const p = page();
  const first = new p.Input('First'); const second = new p.Input('Second');
  p.document.elements = [first, second];
  const controls = p.adapter.snapshot().controls;
  const result = p.adapter.execute({action: 'type', elementId: controls[1].elementId, text: 'A real draft'});
  assert.equal(result.ok, true); assert.equal(first.value, ''); assert.equal(second.value, 'A real draft');
  assert.equal(second.setterCalls, 1); assert.equal(second.events[0].type, 'input');
});

test('contenteditable editors receive text, not a useless value property', () => {
  const p = page(); const editor = new p.Element('DIV', 'Message'); editor.isContentEditable = true;
  p.document.elements = [editor];
  const id = p.adapter.snapshot().controls[0].elementId;
  assert.equal(p.adapter.execute({action: 'type', elementId: id, text: 'Hello from Phormi'}).ok, true);
  assert.equal(editor.textContent, 'Hello from Phormi'); assert.equal(editor.value, undefined);
});

test('empty or invented IDs do not click the first page control', () => {
  const p = page(); const button = new p.Element('BUTTON', 'Continue', 'button');
  p.document.elements = [button]; p.adapter.snapshot();
  assert.equal(p.adapter.execute({action: 'click', text: ''}).ok, false);
  assert.equal(p.adapter.execute({action: 'click', elementId: 'invented'}).ok, false);
  assert.equal(button.clicks, 0);
});

test('stale, hidden and readonly controls are not editable', () => {
  const p = page(); const editor = new p.Input('Message'); p.document.elements = [editor];
  const id = p.adapter.snapshot().controls[0].elementId;
  editor.hidden = true;
  assert.equal(p.adapter.execute({action: 'type', elementId: id, text: 'No'}).ok, false);
  editor.hidden = false; editor.readOnly = true;
  assert.equal(p.adapter.execute({action: 'type', elementId: id, text: 'No'}).ok, false);
  editor.isConnected = false;
  assert.equal(p.adapter.execute({action: 'click', elementId: id}).ok, false);
});

test('submit/send/delete controls are flagged for native confirmation', () => {
  const p = page(); p.document.elements = [new p.Element('BUTTON', 'Save', 'submit'), new p.Element('BUTTON', 'Send message', 'button'), new p.Element('BUTTON', 'Delete account', 'button')];
  for (const control of p.adapter.snapshot().controls) {
    assert.equal(p.adapter.inspect({action: 'click', elementId: control.elementId}).requiresConfirmation, true);
  }
});

test('Enter never submits a sensitive field', () => {
  const p = page(); p.document.activeElement = new p.Input('PIN code', 'password');
  assert.equal(p.adapter.execute({action: 'press_enter'}).ok, false);
});

test('control IDs survive snapshots but removed controls are invalidated', () => {
  const p = page(); const editor = new p.Input('Draft'); p.document.elements = [editor];
  const first = p.adapter.snapshot().controls[0].elementId;
  assert.equal(p.adapter.snapshot().controls[0].elementId, first);
  p.document.elements = []; p.adapter.snapshot();
  assert.equal(p.adapter.execute({action: 'type', elementId: first, text: 'No'}).ok, false);
});
