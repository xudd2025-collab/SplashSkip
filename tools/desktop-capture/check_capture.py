"""Checks for binary transport and safe embedding of untrusted raw UI text."""
import io, json, struct, tempfile, unittest
import base64,gzip
from pathlib import Path
from capture import read_packet
from inspect_capture import render
from augment_visual import merge
from capture_adb import decode_reply,read_records


def packet(meta, image=b''):
    raw=json.dumps(meta).encode()
    return b'SSCP'+struct.pack('>I',len(raw))+raw+struct.pack('>I',len(image))+image


class CaptureChecks(unittest.TestCase):
    def test_compressed_tree_and_record_pages(self):
        payload={'tree_nodes':[{'parent':-1,'text':'close'}]}
        raw=base64.b64encode(gzip.compress(json.dumps(payload).encode())).decode()
        self.assertEqual(decode_reply(('Result: Bundle[{encoding=gzip, data='+raw+'}]').encode()),payload)
        pages=[dict(records=[{'id':'a'}],next_offset=1),dict(records=[{'id':'b'}],next_offset=-1)]
        def run(*args):
            page=pages[int(args[-1].split(':')[-1])]
            return ('data='+base64.b64encode(json.dumps(page).encode()).decode()).encode()
        self.assertEqual([r['id'] for r in read_records(run,'test.app')['records']],['a','b'])
        too_large=base64.b64encode(gzip.compress(b'x'*(2*1024*1024+1))).decode()
        with self.assertRaises(ValueError):decode_reply(('encoding=gzip, data='+too_large).encode())

    def test_visual_does_not_fabricate_native_edge(self):
        native=[dict(index=0,bounds=[0,0,1000,2000],visible=True,text='',description='')]
        frame=dict(width=1000,height=2000,image_width=500,image_height=1000,tree_nodes=native)
        ocr=dict(status='inspection',boxes=1,recognized=1,words=[dict(text='close',confidence=.99,role='close',bounds=[100,20,140,40])])
        out=merge(frame,ocr);v=out['visual_nodes'][0]
        self.assertEqual(out['tree_nodes'],native)
        self.assertEqual(v['bounds'],[200,40,280,80])
        self.assertEqual(v['parent'],-1)
        self.assertEqual(v['anchor_native_index'],0)
        self.assertFalse(v['native_action_available'])
        self.assertFalse(v['clickable'])

    def test_driver_output_at_packet_boundary(self):
        image=b'\xff\xd8helloSSCPpayload'
        stream=io.BytesIO(b'driver startup\n'+packet({'kind':'screen'},image)+packet({'kind':'done'}))
        self.assertEqual(read_packet(stream),({'kind':'screen'},image))
        self.assertEqual(read_packet(stream),({'kind':'done'},b''))

    def test_oversize_and_truncation(self):
        with self.assertRaises(ValueError):read_packet(io.BytesIO(b'SSCP'+struct.pack('>I',5*1024*1024)))
        with self.assertRaises(EOFError):read_packet(io.BytesIO(packet({'kind':'screen'},b'\xff\xd8test')[:-2]))
        with self.assertRaises(ValueError):read_packet(io.BytesIO(b'x'*4101))

    def test_ui_text_cannot_close_script(self):
        with tempfile.TemporaryDirectory() as directory:
            render(directory,[{'text':'</script><img src=x onerror=alert(1)>&'}])
            page=(Path(directory)/'inspect.html').read_text(encoding='utf-8')
            self.assertNotIn('</script><img',page)
            self.assertIn('\\u003c/script\\u003e',page)


if __name__=='__main__':unittest.main()
