"""Cloud Chromium checks for this isolated, synthetic-data visual prototype.

Run with an existing server: python verify.py http://127.0.0.1:4180
Requires the environment's Playwright and Chromium, not application dependencies.
"""
import hashlib
import json
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = Path(__file__).resolve().parent
OUT = BASE / 'evidence'
OUT.mkdir(exist_ok=True)
URL = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:4180'
checks, errors, requests = [], [], []


def check(name, condition):
    checks.append({'check': name, 'passed': bool(condition)})
    if not condition:
        raise AssertionError(name)


with sync_playwright() as p:
    browser = p.chromium.launch(executable_path='/usr/bin/chromium', headless=True,
                                args=['--no-sandbox'])
    page = browser.new_page(viewport={'width': 1440, 'height': 1000}, device_scale_factor=1)
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.on('request', lambda request: requests.append(request.url))
    response = page.goto(URL, wait_until='networkidle')
    check('HTTP document renders successfully', response.status == 200)
    check('Default layout is writing with an unobscured paper',
          page.locator('#side').evaluate("e=>getComputedStyle(e).display==='none'"))
    text = page.locator('#paperContent').inner_text()
    check('Same substantive calculus note, definitions and example are present',
          len(text) > 500 and 'y = 2x − 1' in text and '左右极限不同' in text)

    def shot(name):
        page.wait_for_timeout(250)  # Finish the reference-inspired 200ms material transition.
        page.screenshot(path=str(OUT / name))

    shot('blend-writing.png')
    paper_pixels = []
    for style in ['blend', 'card', 'liquid']:
        page.locator(f'.styles [data-style={style}]').click()
        page.wait_for_timeout(250)
        check(style + ': selected style is truthful',
              page.locator(f'.styles [data-style={style}]').get_attribute('aria-pressed') == 'true'
              and page.locator('html').get_attribute('data-style') == style)
        check(style + ': paper is opaque and unfiltered', page.locator('#paper').evaluate(
            "e=>{const s=getComputedStyle(e);return s.backgroundColor==='rgb(255, 254, 251)' && s.filter==='none' && s.backdropFilter==='none'}"))
        check(style + ': note text is unchanged', page.locator('#paperContent').inner_text() == text)
        box = page.locator('#paper').bounding_box()
        paper_pixels.append(page.screenshot(clip={'x': box['x'] + 4, 'y': box['y'] + 4,
                                                'width': box['width'] - 8, 'height': 600}))
        page.locator('#layerToggle').click()
        shot(style + '-layers.png')
        page.locator('#layerToggle').click()
        page.locator('#mapToggle').click()
        shot(style + '-map.png')
        check(style + ': mind map keeps the paper visible',
              page.locator('#paperArea').bounding_box()['width'] > 650
              and page.locator('#mapPanel').is_visible())
        page.locator('#mapToggle').click()
    check('Visible paper pixels are identical across all three material styles',
          len({hashlib.sha256(data).hexdigest() for data in paper_pixels}) == 1)

    page.locator('.styles [data-style=blend]').click()
    for theme in ['sky', 'warm', 'mist']:
        page.locator(f'[data-theme={theme}].theme-button').click()
        check(theme + ': environment theme switches',
              page.locator('html').get_attribute('data-theme') == theme)

    page.get_by_role('button', name='朱砂', exact=True).click()
    page.get_by_role('button', name='粗笔 5', exact=True).click()
    paper = page.locator('#paper').bounding_box()
    x, y = paper['x'] + 100, paper['y'] + 120
    page.mouse.move(x, y)
    page.mouse.down()
    page.mouse.move(x + 150, y + 15, steps=18)
    page.mouse.up()
    path = page.locator('#ink path')
    check('Selected pen color and width affect a real trial stroke',
          path.count() == 1 and path.get_attribute('stroke') == '#a35550'
          and path.get_attribute('stroke-width') == '5')
    page.get_by_role('button', name='撤销试写', exact=True).click()
    check('Undo removes the trial stroke', page.locator('#ink path').count() == 0)
    page.get_by_role('button', name='重做试写', exact=True).click()
    check('Redo restores the trial stroke', page.locator('#ink path').count() == 1)

    page.locator('#layerToggle').click()
    page.get_by_role('button', name='隐藏学习批注', exact=True).click()
    page.wait_for_function("getComputedStyle(document.querySelector('.annotation-text')).opacity==='0'")
    check('Hiding the active layer hides its ink and original annotations',
          page.locator('#ink path').count() == 0 and
          page.locator('.annotation-text').first.evaluate("e=>getComputedStyle(e).opacity==='0'"))
    page.mouse.move(x, y + 50)
    page.mouse.down()
    page.mouse.move(x + 70, y + 50)
    page.mouse.up()
    page.get_by_role('button', name='显示学习批注', exact=True).click()
    check('Hidden layers reject trial ink without silently switching layers',
          page.locator('#ink path').count() == 1)
    page.locator('#opacity').fill('40')
    check('Layer opacity changes the rendered ink',
          page.locator('#ink path').get_attribute('opacity') == '0.4')
    page.locator('#opacity').fill('100')
    page.get_by_role('button', name='显示推导草稿', exact=True).click()
    page.locator('[data-layer-select=draft]').click()
    check('Draft selection and visibility both work',
          page.locator('#draftNote').is_visible() and '推导草稿' in page.locator('#layerStatus').inner_text())
    page.locator('#closeLayers').click()
    page.get_by_role('button', name='荧光笔', exact=True).click()
    paper = page.locator('#paper').bounding_box()
    x, y = paper['x'] + 110, paper['y'] + 200
    page.mouse.move(x, y)
    page.mouse.down()
    page.mouse.move(x + 140, y + 2, steps=15)
    page.mouse.up()
    check('Highlighter creates translucent ink on selected layer',
          page.locator('#ink path').count() == 2 and
          page.locator('#ink path').last.get_attribute('opacity') == '0.3')
    page.get_by_role('button', name='擦除试写笔迹', exact=True).click()
    page.mouse.click(x + 50, y + 1)
    check('Eraser removes hit trial stroke only', page.locator('#ink path').count() == 1)
    page.locator('#undo').click()
    check('Erasing a stroke can itself be undone', page.locator('#ink path').count() == 2)
    page.locator('#redo').click()
    check('Erasing can be redone', page.locator('#ink path').count() == 1)
    page.locator('#readToggle').click()
    check('Reading mode allows underlying document interaction',
          page.locator('#ink').evaluate("e=>getComputedStyle(e).pointerEvents==='none'"))
    page.mouse.move(x, y)
    page.mouse.down()
    page.mouse.move(x + 50, y + 2)
    page.mouse.up()
    check('Reading mode does not add ink', page.locator('#ink path').count() == 1)

    page.locator('#layerToggle').click()
    page.get_by_role('button', name='隐藏推导草稿', exact=True).click()
    page.locator('[data-layer-select=notes]').click()
    page.locator('#closeLayers').click()
    page.locator('[data-tool=pen]').click()
    page.locator('#readToggle').click()
    page.get_by_role('button', name='中笔 3', exact=True).click()
    page.get_by_role('button', name='靛蓝', exact=True).click()
    page.wait_for_selector('#toast', state='hidden')
    page.locator('#mapToggle').click()
    page.locator('[data-section=example]').click()
    page.locator('#sourceJump').click()
    area, target = page.locator('#paperArea').bounding_box(), page.locator('#example').bounding_box()
    check('Map source action scrolls actual corresponding section into view',
          area['y'] <= target['y'] < area['y'] + area['height'] - 100)
    shot('blend-source-jump.png')
    page.locator('#mapToggle').click()
    check('Closing map retains actual source scroll context', page.locator('#paperArea').evaluate('e=>e.scrollTop') > 100)
    page.locator('#info').click()
    check('Prototype limitations are visible in the dialog', page.locator('#about').is_visible()
          and '不保存' in page.locator('#about').inner_text())
    page.keyboard.press('Escape')
    check('Dialog supports Escape and returns focus', not page.locator('#about').is_visible()
          and page.locator('#info').evaluate('e=>e===document.activeElement'))
    page.reload(wait_until='networkidle')
    check('Reload resets trial ink and does not pretend to save', page.locator('#ink path').count() == 0)
    check('No persistent storage is written', page.evaluate('localStorage.length===0 && sessionStorage.length===0'))

    for width, height in [(1200, 800), (800, 1100), (390, 844), (375, 800)]:
        page.set_viewport_size({'width': width, 'height': height})
        page.goto(URL + '?panel=map', wait_until='networkidle')
        page.wait_for_timeout(200)
        check(f'{width}: no page-level horizontal overflow', page.evaluate('document.documentElement.scrollWidth===innerWidth'))
        area = page.locator('#paperArea').bounding_box()
        panel = page.locator('#mapPanel').bounding_box()
        check(f'{width}: map is not fullscreen and source stays readable',
              area['height'] >= 180 and area['width'] >= 280 and panel['height'] >= 220)
        if width < 650:
            instant = page.locator('#instant').bounding_box()
            check(f'{width}: selected definition is actually visible with map',
                  area['y'] <= instant['y'] < area['y'] + area['height'] - 100)
            last = page.locator('[data-section=example]').bounding_box()
            tree = page.locator('.map-tree').bounding_box()
            check(f'{width}: all four map concepts fit without clipping',
                  last['y'] + last['height'] <= tree['y'] + tree['height'] + 1)
            read = page.locator('#readToggle').bounding_box()
            check(f'{width}: reading toggle stays reachable', 0 <= read['x'] and read['x'] + read['width'] <= width)
        shot(f'map-{width}.png')
        page.locator('#layerToggle').click()
        side = page.locator('#side').bounding_box()
        footer = page.locator('.statusbar').bounding_box()
        if width < 650:
            check(f'{width}: switching panels avoids cramped simultaneous narrow panels',
                  page.locator('#mapPanel').is_hidden() and page.locator('#layerPanel').is_visible())
        else:
            mapbox = page.locator('#mapPanel').bounding_box()
            check(f'{width}: simultaneous panels stay above status bar',
                  mapbox['y'] + mapbox['height'] <= footer['y'] + 1)
        if width == 375:
            shot('narrow-layers.png')

    page.emulate_media(reduced_motion='reduce')
    page.locator('.styles [data-style=liquid]').click()
    check('Reduced motion disables spring transitions',
          page.locator('.styles button').first.evaluate("e=>getComputedStyle(e).transitionDuration==='0s'"))
    check('No JavaScript runtime errors', not errors)
    check('Application makes no external resource or data requests',
          all(url.startswith(URL) for url in requests))
    browser.close()

report = {'browser': 'System Chromium via Playwright, cloud Linux',
          'rendering': 'Web, not native Android', 'url': URL,
          'passed': len(checks), 'checks': checks, 'runtime_errors': errors,
          'note': 'Synthetic content only. No device, native latency, pressure, palm rejection or persistence claims.'}
(OUT / 'validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps({'passed': len(checks), 'runtime_errors': errors}, ensure_ascii=False))
