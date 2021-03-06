/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.rtcp;

import java.io.*;

import net.sf.fmj.media.rtp.*;
import net.sf.fmj.media.rtp.util.*;

import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.service.neomedia.event.*;
import org.jitsi.util.*;

/**
 * Created by gp on 6/25/14.
 */
public class RTCPPacketParserEx
    extends net.sf.fmj.media.rtp.RTCPPacketParser
{
    /**
     * The <tt>Logger</tt> used by the <tt>RTCPPacketParserEx</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(RTCPPacketParserEx.class);

    public RTCPPacket parse(byte[] data, int offset, int length)
        throws BadFormatException
    {
        UDPPacket udp = new UDPPacket();

        udp.data = data;
        udp.length = length;
        udp.offset = offset;
        udp.received = false;
        return parse(udp);
    }

    @Override
    protected RTCPPacket parse(
            RTCPCompoundPacket base,
            int firstbyte /* without version/padding */,
            int type,
            int length /* in actual bytes */,
            DataInputStream in)
        throws BadFormatException, IOException
    {
        if (type ==  RTCPFBPacket.RTPFB || type ==  RTCPFBPacket.PSFB)
        {
/*
    0                   1                   2                   3
    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |V=2|P|   FMT   |       PT      |          length               |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                  SSRC of packet sender                        |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                  SSRC of media source                         |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   :            Feedback Control Information (FCI)                 :
   :                                                               :
*/
            long senderSSRC = in.readInt() & 0xffffffffL;
            long sourceSSRC = in.readInt() & 0xffffffffL;

            if (type == RTCPFBPacket.RTPFB)
            {
                return
                    parseRTCPFBPacket(
                            base,
                            firstbyte,
                            RTCPFBPacket.RTPFB,
                            length,
                            in,
                            senderSSRC,
                            sourceSSRC);
            }
            else
            {
                switch (firstbyte)
                {
                    case RTCPREMBPacket.FMT: // REMB
/*
    0                   1                   2                   3
    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |V=2|P| FMT=15  |   PT=206      |             length            |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                  SSRC of packet sender                        |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |                  SSRC of media source                         |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |  Unique identifier 'R' 'E' 'M' 'B'                            |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |  Num SSRC     | BR Exp    |  BR Mantissa                      |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |   SSRC feedback                                               |
   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
   |  ...                                                          |
 */
                        RTCPREMBPacket remb = new RTCPREMBPacket(base);

                        remb.senderSSRC = senderSSRC;
                        remb.sourceSSRC = sourceSSRC;

                        // Unique identifier 'R' 'E' 'M' 'B'
                        in.readInt();

                        int destlen = in.readUnsignedByte();

                        byte[] buf = new byte[3];
                        in.read(buf);
                        remb.exp = (buf[0] & 0xFC) >> 2;
                        remb.mantissa
                            = ((buf[0] & 0x3) << 16) & 0xFF0000
                                | (buf[1] << 8) & 0x00FF00
                                | buf[2] & 0x0000FF;

                        remb.dest = new long[destlen];
                        for (int i = 0; i < remb.dest.length; i++)
                            remb.dest[i] = in.readInt() & 0xffffffffL;

                        return remb;
                    default:
                        return
                            parseRTCPFBPacket(
                                    base,
                                    firstbyte,
                                    RTCPFBPacket.PSFB,
                                    length,
                                    in,
                                    senderSSRC,
                                    sourceSSRC);
                }
            }
        }
        else
            return null;
    }

    private RTCPFBPacket parseRTCPFBPacket(
            RTCPCompoundPacket base,
            int firstbyte,
            int type,
            int length /* in actual bytes */,
            DataInputStream in,
            long senderSSRC,
            long sourceSSRC)
        throws IOException
    {
        RTCPFBPacket fb;

        if (type == RTCPFBPacket.RTPFB && firstbyte == NACKPacket.FMT)
            fb = new NACKPacket(base);
        else
            fb = new RTCPFBPacket(base);

        fb.fmt = firstbyte;
        fb.type = type;
        fb.senderSSRC = senderSSRC;
        fb.sourceSSRC = sourceSSRC;

        int fcilen = length - 12; // header + ssrc + ssrc = 14

        if (fcilen != 0)
        {
            fb.fci = new byte[fcilen];
            in.read(fb.fci);
        }

        if (logger.isTraceEnabled())
        {
            String ptStr; // Payload type (PT)
            String fmtStr = null; // Feedback message type (FMT)
            String detailStr = null;

            switch (fb.type)
            {
            case RTCPFBPacket.PSFB:
                ptStr = "PSFB";
                switch (fb.fmt)
                {
                case RTCPFeedbackMessageEvent.FMT_FIR:
                    fmtStr = "FIR";
                    break;
                case RTCPFeedbackMessageEvent.FMT_PLI:
                    fmtStr = "PLI";
                    break;
                case RTCPREMBPacket.FMT:
                    fmtStr = "REMB";
                    break;
                }
                break;
            case RTCPFBPacket.RTPFB:
                ptStr = "RTPFB";
                switch (fb.fmt)
                {
                case 1: /* Generic NACK */
                    fmtStr = "Generic NACK";
                    break;
                case 3: /* Temporary Maximum Media Stream Bit Rate Request (TMMBR) */
                    fmtStr = "TMMBR";
                    break;
                case 4: /* Temporary Maximum Media Stream Bit Rate Notification (TMMBN) */
                    fmtStr = "TMMBN";

                    // Log the TMMBN FCI entries.
                    StringBuilder tmmbnFciEntryStr = new StringBuilder();

                    for (int i = 0, end = fcilen - 8; i < end; i += 8)
                    {
                        int ssrc = RTPTranslatorImpl.readInt(fb.fci, i);
                        byte b4 = fb.fci[i + 4];
                        int mxTbrExp /* 6 bits */ = (b4 & 0xFC) >>> 2;
                        byte b6 = fb.fci[i + 6];
                        int mxTbrMantissa /* 17 bits */
                            = (((b4 & 0x1) << 16) & 0xFF0000)
                                | ((fb.fci[i + 5] << 8) & 0x00FF00)
                                | (b6 & 0x0000FF);
                        int measuredOverhead /* 9 bits */
                            = (((b6 & 0x1) << 8) & 0xFF00)
                                | (fb.fci[i + 7] & 0x00FF);

                        tmmbnFciEntryStr.append(", SSRC 0x");
                        tmmbnFciEntryStr.append(
                                Long.toHexString(ssrc & 0xFFFFFFFFL));
                        tmmbnFciEntryStr.append(", MxTBR Exp ");
                        tmmbnFciEntryStr.append(mxTbrExp);
                        tmmbnFciEntryStr.append(", MxTBR Mantissa ");
                        tmmbnFciEntryStr.append(mxTbrMantissa);
                        tmmbnFciEntryStr.append(", Measured Overhead ");
                        tmmbnFciEntryStr.append(measuredOverhead);
                    }
                    detailStr = tmmbnFciEntryStr.toString();
                    break;
                }
                break;
            default:
                ptStr = Integer.toString(fb.type);
                break;
            }
            if (fmtStr == null)
                fmtStr = Integer.toString(fb.fmt);
            if (detailStr == null)
                detailStr = "";
            logger.trace(
                    "SSRC of packet sender: 0x" + Long.toHexString(senderSSRC)
                        + " (" + senderSSRC + "), SSRC of media source: 0x"
                        + Long.toHexString(sourceSSRC) + " (" + sourceSSRC
                        + "), Payload type (PT): " + ptStr
                        + ", Feedback message type (FMT): " + fmtStr
                        + detailStr);
        }

        return fb;
    }
}
