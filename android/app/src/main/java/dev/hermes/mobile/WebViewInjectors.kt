package dev.hermes.mobile

import android.webkit.WebView

object WebViewInjectors {
    internal fun injectMobileChrome(view: WebView, showingConnectionHub: Boolean) {
        if (showingConnectionHub) return
        view.evaluateJavascript(
            """
            (function(){
              if(!document.getElementById('hermes-mobile-client-style')){
                var style=document.createElement('style');
                style.id='hermes-mobile-client-style';
                style.textContent=[
                  'html,body,#root{touch-action:pan-x pan-y;-webkit-overflow-scrolling:touch;}',
                  'body,*{overscroll-behavior:auto;}',
                  'input,textarea,select{font-size:16px!important;}',
                  '#hermes-mobile-power,#hermes-mobile-textsize,#hermes-mobile-connector{width:34px;height:34px;border-radius:999px;border:1px solid rgba(255,230,203,.45);background:rgba(4,28,28,.25);color:#ffe6cb;display:inline-flex;align-items:center;justify-content:center;text-decoration:none;backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px);line-height:1;box-sizing:border-box;}',
                  '#hermes-mobile-power{font-size:17px;}',
                  '#hermes-mobile-textsize{font-size:15px;}',
                  '#hermes-mobile-connector{font-size:18px;color:#65ff9a;border-color:rgba(101,255,154,.45);}',
                  '#hermes-mobile-power:hover,#hermes-mobile-power:active,#hermes-mobile-textsize:hover,#hermes-mobile-textsize:active,#hermes-mobile-connector:hover,#hermes-mobile-connector:active{background:rgba(255,215,94,.12);border-color:rgba(255,215,94,.75);color:#ffd75e;}',
                  '#hermes-mobile-dead-session{position:fixed;left:16px;right:16px;bottom:74px;z-index:99998;display:none;align-items:center;justify-content:space-between;gap:14px;padding:14px 16px;border:1px solid rgba(255,215,94,.6);border-radius:18px;background:linear-gradient(135deg,rgba(4,28,28,.96),rgba(9,46,40,.94));color:#ffe6cb;box-shadow:0 18px 60px rgba(0,0,0,.45);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);font-family:monospace;}',
                  '#hermes-mobile-dead-session.is-visible{display:flex;}',
                  '#hermes-mobile-dead-session strong{display:block;color:#ffd75e;font-size:12px;letter-spacing:.12em;text-transform:uppercase;margin-bottom:3px;}',
                  '#hermes-mobile-dead-session span{font-size:12px;opacity:.8;}',
                  '#hermes-mobile-dead-session a{white-space:nowrap;color:#031818;background:#ffd75e;border:1px solid rgba(255,230,203,.55);border-radius:999px;padding:10px 13px;text-decoration:none;font-size:12px;font-weight:700;}'
                ].join('\n');
                document.head.appendChild(style);
              }

              if(!document.getElementById('hermes-mobile-power')){
                var b=document.createElement('a');
                b.id='hermes-mobile-power';
                b.href='${HermesConfig.AppScheme.URL_MENU}';
                b.setAttribute('aria-label','Power');
                b.setAttribute('title','Power');
                b.innerHTML='<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/></svg>';
                var z=document.createElement('a');
                z.id='hermes-mobile-textsize';
                z.href='${HermesConfig.AppScheme.URL_TEXT_SIZE}';
                z.setAttribute('aria-label','Text Size');
                z.setAttribute('title','Text Size');
                z.textContent='A';
                var d=document.createElement('a');
                d.id='hermes-mobile-connector';
                d.href='${HermesConfig.AppScheme.URL_CONNECTOR}';
                d.setAttribute('aria-label','Connector State');
                d.setAttribute('title','Connector State');
                d.textContent='●';

                var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
                var brandText=null;
                while(walker.nextNode()){
                  var t=(walker.currentNode.nodeValue||'').replace(/\s+/g,' ').trim().toUpperCase();
                  if(t.indexOf('HERMES')!==-1 && t.indexOf('AGENT')!==-1){
                    brandText=walker.currentNode.parentElement;
                    break;
                  }
                }
                var host=brandText;
                while(host && host!==document.body){
                  var r=host.getBoundingClientRect();
                  if(r.width>60 && r.height>20) break;
                  host=host.parentElement;
                }
                if(host && host!==document.body){
                  host.style.display='flex';
                  host.style.alignItems='center';
                  host.style.justifyContent='space-between';
                  host.style.gap='10px';
                  var controls=document.createElement('div');
                  controls.style.display='inline-flex';
                  controls.style.gap='8px';
                  controls.appendChild(d);
                  controls.appendChild(z);
                  controls.appendChild(b);
                  host.appendChild(controls);
                }else{
                  d.style.position='fixed';
                  d.style.top='max(12px, calc(env(safe-area-inset-top, 0px) + 8px))';
                  d.style.left='12px';
                  d.style.zIndex='99999';
                  z.style.position='fixed';
                  z.style.top='max(12px, calc(env(safe-area-inset-top, 0px) + 8px))';
                  z.style.left='54px';
                  z.style.zIndex='99999';
                  b.style.position='fixed';
                  b.style.top='max(12px, calc(env(safe-area-inset-top, 0px) + 8px))';
                  b.style.left='96px';
                  b.style.zIndex='99999';
                  document.body.appendChild(d);
                  document.body.appendChild(z);
                  document.body.appendChild(b);
                }
              }

              if(!document.getElementById('hermes-mobile-dead-session')){
                var dead=document.createElement('div');
                dead.id='hermes-mobile-dead-session';
                dead.innerHTML='<div><strong>TUI session ended</strong><span>Open a fresh Hermes terminal and resume there.</span></div><a href="${HermesConfig.AppScheme.URL_RELOAD_TUI}">Open fresh TUI</a>';
                document.body.appendChild(dead);
              }
              if(!window.__HermesMobileDeadSessionWatch){
                window.__HermesMobileDeadSessionWatch=true;
                var checkDead=function(){
                  var dead=document.getElementById('hermes-mobile-dead-session');
                  if(!dead) return;
                  var text=(document.body&&document.body.innerText||'').toLowerCase();
                  var ended=text.indexOf('[session ended]')!==-1
                    || text.indexOf('gateway exited')!==-1
                    || text.indexOf('chat unavailable')!==-1;
                  dead.classList.toggle('is-visible', ended);
                };
                setInterval(checkDead,1500);
                new MutationObserver(checkDead).observe(document.body,{childList:true,subtree:true,characterData:true});
                setTimeout(checkDead,300);
              }

            })();
            """.trimIndent(),
            null,
        )
    }

    internal fun triggerTerminalRelayout(view: WebView) {
        view.postDelayed({
            view.evaluateJavascript(
                """
                (function(){
                  var root = document.documentElement;
                  var prevWidth = root.style.width;
                  // Force a measurable layout delta so xterm ResizeObserver refits columns.
                  root.style.width = 'calc(100% - 1px)';
                  setTimeout(function(){ root.style.width = prevWidth || ''; }, 90);
                  window.dispatchEvent(new Event('orientationchange'));
                  window.dispatchEvent(new Event('resize'));
                  if (window.visualViewport) {
                    window.visualViewport.dispatchEvent(new Event('resize'));
                  }
                })();
                """.trimIndent(),
                null,
            )
        }, 90)
        view.postDelayed({
            view.evaluateJavascript(
                """
                (function(){
                  window.dispatchEvent(new Event('orientationchange'));
                  window.dispatchEvent(new Event('resize'));
                  if (window.visualViewport) {
                    window.visualViewport.dispatchEvent(new Event('resize'));
                  }
                })();
                """.trimIndent(),
                null,
            )
        }, 260)
    }

    internal fun injectTerminalTouchWheelBridge(view: WebView, showingConnectionHub: Boolean) {
        if (showingConnectionHub) return
        view.evaluateJavascript(
            """
            (function(){
              // --- CSS: lock touch ownership to xterm region only ---
              if(!document.getElementById('hermes-terminal-touch-wheel-style')){
                var style=document.createElement('style');
                style.id='hermes-terminal-touch-wheel-style';
                // touch-action:none on .xterm subtree prevents the browser from starting
                // a native scroll gesture when the finger is inside the terminal.
                // Only the .xterm root and its children — NOT the whole page.
                style.textContent='.xterm,.xterm *{touch-action:none!important;overscroll-behavior:contain!important;}';
                document.head.appendChild(style);
              }

              if(window.__HermesTerminalTouchWheelBridge) {
                window.__HermesTerminalTouchWheelBridge.install();
                return;
              }

              // Only install on .xterm root elements — NOT on .xterm-viewport or
              // .xterm-scrollable-element. Installing on children causes duplicate
              // events: one touchmove fires per installed element AND the synthetic
              // WheelEvent bubbles through all of them.
              function xtermRoots(){
                return Array.prototype.slice.call(document.querySelectorAll('.xterm'));
              }

              function installOn(host){
                if(!host || host.__hermesTouchWheelBridgeInstalled) return;
                // Guard: only install on actual .xterm root (not nested xterm classes)
                if(!host.classList.contains('xterm')) return;
                host.__hermesTouchWheelBridgeInstalled=true;

                var lastX=null;
                var lastY=null;

                // DISPATCH TARGET: .xterm-scrollable-element, NOT .xterm
                //
                // xterm 6.x DOM hierarchy:
                //   .xterm  (CoreBrowserTerminal.element)
                //     └── .xterm-scrollable-element  (SmoothScrollableElement._domNode)
                //           └── .xterm-screen
                //
                // Two independent wheel listeners exist:
                //   A) SmoothScrollableElement on .xterm-scrollable-element {passive:false}
                //      -> calls _onMouseWheel -> setScrollPosition (raw PIXEL scroll)
                //   B) CoreBrowserTerminal on .xterm {passive:false}
                //      -> ChatPage handler: term.scrollLines(FIXED_STEP) + ev.preventDefault()
                //         scrollLines() uses a fixed step regardless of deltaY magnitude.
                //
                // Problem with dispatching to .xterm (old approach):
                //   B fires first -> term.scrollLines(fixed step) regardless of finger speed
                //   -> fast swipe (large deltaY, few events) scrolls LESS than slow swipe
                //      (small deltaY, many events) — inverted scroll speed.
                //
                // Fix: dispatch to .xterm-scrollable-element with bubbles:false
                //   A fires (on target) -> raw pixel scroll proportional to deltaY
                //   B never fires (bubbles:false, so event doesn't reach .xterm ancestor)
                //   -> scroll distance is directly proportional to finger movement speed.
                function wheelTarget(host){
                  return host.querySelector('.xterm-scrollable-element') || host;
                }

                // touchstart: passive:false so we can preventDefault if needed.
                // preventDefault here stops browser from "locking in" a scroll direction
                // before touchmove fires (important on some Android versions).
                host.addEventListener('touchstart',function(event){
                  if(!event.touches || event.touches.length < 1) return;
                  lastX=event.touches[0].clientX;
                  lastY=event.touches[0].clientY;
                  // Don't preventDefault on touchstart — it breaks tap-to-focus on xterm.
                  event.stopPropagation();
                },{passive:false});

                host.addEventListener('touchmove',function(event){
                  if(!event.touches || event.touches.length < 1 || lastX === null || lastY === null) return;
                  var touch=event.touches[0];
                  // delta = finger movement direction.
                  // .xterm-scrollable-element's _onMouseWheel uses the same sign convention
                  // as a real mouse wheel: positive deltaY = scroll DOWN (content moves up).
                  // Finger swipes UP → content should move UP → deltaY must be positive.
                  // Finger swipes UP → touch.clientY decreases → (lastY - touch.clientY) > 0 ✓
                  // Finger swipes DOWN → content moves DOWN → deltaY must be negative.
                  // Finger swipes DOWN → touch.clientY increases → (lastY - touch.clientY) < 0 ✓
                  // BUT: .xterm-scrollable-element treats positive deltaY as scroll UP visually
                  // (viewport scrollTop increases = content scrolls up on screen), which is the
                  // OPPOSITE of the natural swipe expectation. Negate to match natural direction.
                  var deltaX=touch.clientX - lastX;
                  var deltaY=touch.clientY - lastY;
                  lastX=touch.clientX;
                  lastY=touch.clientY;
                  // Prevent native scroll AND stop propagation so parent overflows
                  // (html/body with overflow-y:auto on mobile) don't scroll.
                  event.preventDefault();
                  event.stopPropagation();
                  var wheel=new WheelEvent('wheel',{
                    deltaX:deltaX,
                    deltaY:deltaY,
                    deltaMode:WheelEvent.DOM_DELTA_PIXEL,
                    bubbles:false,
                    cancelable:true
                  });
                  // Dispatch to .xterm-scrollable-element (pixel-scroll handler).
                  // bubbles:false prevents the event from reaching CoreBrowserTerminal's
                  // line-step handler on .xterm, which would override pixel scroll.
                  wheelTarget(host).dispatchEvent(wheel);
                },{passive:false});

                function reset(){
                  lastX=null;
                  lastY=null;
                }

                host.addEventListener('touchend',reset,{passive:true});
                host.addEventListener('touchcancel',reset,{passive:true});
              }

              function install(){
                xtermRoots().forEach(installOn);
              }

              window.__HermesTerminalTouchWheelBridge={install:install};
              install();

              if(document.body){
                new MutationObserver(install).observe(document.body,{childList:true,subtree:true});
              }
            })();
            """.trimIndent(),
            null,
        )
    }

    internal fun injectMobileInputBridge(view: WebView, showingConnectionHub: Boolean) {
        if (showingConnectionHub) return
        view.evaluateJavascript(
            """
            (function(){
              if(window.${HermesConfig.JS_BRIDGE_INPUT}) return;

              function terminalTarget(){
                return document.querySelector('.xterm-helper-textarea')
                  || document.querySelector('.xterm textarea')
                  || document.querySelector('.xterm');
              }

              function focusTerminal(){
                var target=terminalTarget();
                if(target && target.focus) target.focus();
                return target;
              }

              function fire(target,type,init){
                var ev;
                try {
                  ev = new InputEvent(type, Object.assign({bubbles:true,cancelable:true,composed:true}, init || {}));
                } catch (_) {
                  ev = document.createEvent('Event');
                  ev.initEvent(type,true,true);
                  Object.assign(ev, init || {});
                }
                target.dispatchEvent(ev);
              }

              function keyEvent(target,type,key,code,keyCode,extra){
                var ev;
                var opts = Object.assign({
                  key:key,
                  code:code,
                  bubbles:true,
                  cancelable:true,
                  composed:true,
                  keyCode:keyCode,
                  which:keyCode
                }, extra || {});
                try {
                  ev = new KeyboardEvent(type, opts);
                } catch (_) {
                  ev = document.createEvent('KeyboardEvent');
                  ev.initKeyboardEvent(type,true,true,window,key,0,false,false,false,false);
                }
                target.dispatchEvent(ev);
              }

              function sendText(text){
                var target=focusTerminal();
                if(!target) return false;
                if(target.tagName === 'TEXTAREA' || target.tagName === 'INPUT'){
                  target.value = text;
                  fire(target,'beforeinput',{inputType:'insertText',data:text});
                  fire(target,'input',{inputType:'insertText',data:text});
                  target.value = '';
                  return true;
                }
                keyEvent(target,'keydown',text,'',text.charCodeAt(0));
                keyEvent(target,'keypress',text,'',text.charCodeAt(0));
                keyEvent(target,'keyup',text,'',text.charCodeAt(0));
                return true;
              }

              function sendKey(key){
                var target=focusTerminal();
                if(!target) return false;
                var spec={
                  backspace:['Backspace','Backspace',8,'deleteContentBackward'],
                  delete:['Delete','Delete',46,'deleteContentForward'],
                  enter:['Enter','Enter',13,'insertLineBreak'],
                  up:['ArrowUp','ArrowUp',38,null],
                  down:['ArrowDown','ArrowDown',40,null],
                  left:['ArrowLeft','ArrowLeft',37,null],
                  right:['ArrowRight','ArrowRight',39,null]
                }[key];
                if(!spec) return false;
                keyEvent(target,'keydown',spec[0],spec[1],spec[2]);
                if(spec[3]) fire(target,'beforeinput',{inputType:spec[3],data:null});
                if(spec[3]) fire(target,'input',{inputType:spec[3],data:null});
                keyEvent(target,'keyup',spec[0],spec[1],spec[2]);
                if(target.value !== undefined) target.value = '';
                return true;
              }

              window.${HermesConfig.JS_BRIDGE_INPUT} = {
                text: sendText,
                key: sendKey
              };

              // Focus tracker: tell Kotlin when .xterm-helper-textarea gains/loses focus.
              // This lets onCreateInputConnection() route input to xterm only when the
              // terminal is actually focused, and fall back to normal WebView input otherwise.
              (function installFocusTracker() {
                if (window.__hermesFocusTrackerInstalled) return;
                window.__hermesFocusTrackerInstalled = true;

                function notify(focused) {
                  if (window.${HermesConfig.JS_BRIDGE_FOCUS} && window.${HermesConfig.JS_BRIDGE_FOCUS}.setXtermHelperTextareaFocused) {
                    window.${HermesConfig.JS_BRIDGE_FOCUS}.setXtermHelperTextareaFocused(focused);
                  }
                }

                function isXtermTextarea(el) {
                  return !!(el && el.classList && el.classList.contains('xterm-helper-textarea'));
                }

                // Report current focus state immediately on install.
                notify(isXtermTextarea(document.activeElement));

                document.addEventListener('focusin', function(e) {
                  notify(isXtermTextarea(e.target));
                }, true);

                document.addEventListener('focusout', function() {
                  // Use setTimeout to let the new activeElement settle before reporting.
                  setTimeout(function() {
                    notify(isXtermTextarea(document.activeElement));
                  }, 0);
                }, true);

                if (document.body) {
                  // NOTE: MutationObserver intentionally does NOT call notify() here.
                  // DOM mutations during login / React re-renders would trigger rapid-fire
                  // restartInput() calls on the Android side, causing an immediate crash.
                  // Focus state is already tracked accurately by the focusin/focusout listeners.
                  new MutationObserver(function() {
                    // reserved — no IME notify on DOM mutations
                  }).observe(document.body, { childList: true, subtree: true });
                }
              })();
            })();
            """.trimIndent(),
            null,
        )
    }
}
