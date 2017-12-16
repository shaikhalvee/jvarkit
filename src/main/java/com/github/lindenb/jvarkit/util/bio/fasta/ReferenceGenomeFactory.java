/*
The MIT License (MIT)

Copyright (c) 2017 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/
package com.github.lindenb.jvarkit.util.bio.fasta;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.function.BiFunction;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import com.beust.jcommander.IStringConverter;
import com.github.lindenb.jvarkit.lang.AbstractCharSequence;
import com.github.lindenb.jvarkit.lang.JvarkitException;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import htsjdk.samtools.reference.ReferenceSequence;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.RuntimeIOException;

public class ReferenceGenomeFactory
implements IStringConverter<ReferenceGenome>  {

private abstract class AbstractReferenceContigImpl
	extends AbstractCharSequence
	implements ReferenceContig
	{
	private byte buffer[]=null;
	private int buffer_pos=-1;
	private int half_buffer_capacity=1000000;
	
	protected abstract  byte[] refill(int start0,int end0);
	
	@Override
	public char charAt(int index0) {
		if(index0 >= length())
			{
			throw new IndexOutOfBoundsException("index:"+index0);
			}
		if(buffer!=null && index0>=buffer_pos && index0-buffer_pos < buffer.length)
			{
			return (char)buffer[index0-buffer_pos];
			}
		int minStart=Math.max(0, index0-half_buffer_capacity);
		int maxEnd=Math.min(minStart+2*half_buffer_capacity,this.length());
		this.buffer=refill(minStart,maxEnd);
		this.buffer_pos=minStart;
		return (char)buffer[index0-minStart];
		}
	}
	
private  class ReferenceGenomeImpl
	implements ReferenceGenome
	{
	private  class ReferenceContigImpl
		extends AbstractReferenceContigImpl
		{
		final SAMSequenceRecord ssr;
		final ReferenceSequence referenceSequence;

		
		ReferenceContigImpl(final SAMSequenceRecord ssr) {
			this.ssr = ssr;
			this.referenceSequence  = ReferenceGenomeImpl.this.indexedFastaSequenceFile.getSequence(ssr.getSequenceName());
			if(this.referenceSequence==null) throw new IllegalStateException();
			}

		@Override
		protected byte[] refill(int minStart,int maxEnd) {
			return ReferenceGenomeImpl.this.indexedFastaSequenceFile.getSubsequenceAt(
					getContig(),
					minStart+1,
					Math.min(maxEnd,this.length())
					).getBases();
			}
			
		@Override
		public SAMSequenceRecord getSAMSequenceRecord() {
			return this.ssr;
			}
		}
	
	private final File fastaFile;
	private IndexedFastaSequenceFile indexedFastaSequenceFile;
	private SAMSequenceDictionary dictionary;
	private ReferenceContigImpl lastContig =null;
	ReferenceGenomeImpl(final File fastaFile) throws IOException
		{
		this.fastaFile = fastaFile;
		IOUtil.assertFileIsReadable(fastaFile);
		this.indexedFastaSequenceFile = new IndexedFastaSequenceFile(fastaFile);
		this.dictionary = this.indexedFastaSequenceFile.getSequenceDictionary();
		if(this.dictionary==null) {
			throw new JvarkitException.FastaDictionaryMissing(fastaFile);
			}
		}
	@Override
	public SAMSequenceDictionary getDictionary() {
		return this.dictionary;
		}
	@Override
	public String getSource() {
		return this.fastaFile.toString();
		}
	@Override
	public String toString() {
		return getSource();
		}
	
	@Override
	public ReferenceContig getContig(final String contigName) {
		if(this.lastContig!=null && lastContig.getContig().equals(contigName)) {
			return this.lastContig;
			}
		final SAMSequenceRecord ssr=getDictionary().getSequence(contigName);
		this.lastContig=null;
		if(ssr==null) return null;
		final ReferenceContigImpl rci = new ReferenceContigImpl(ssr);
		this.lastContig= rci;
		return rci;
		}
	@Override
	public void close() throws IOException {
		CloserUtil.close(this.indexedFastaSequenceFile);
		}
	}


public ReferenceGenome open(final String ref) throws IOException
	{
	return (IOUtil.isUrl(ref))?
		openDAS(new URL(ref)):
		openFastaFile(new File(ref))
		;
	}

public ReferenceGenome openFastaFile(final File fastaFile) throws IOException
	{
	return new ReferenceGenomeImpl(fastaFile);
	}

/** open a DAS URL */
public ReferenceGenome openDAS(final URL dasUrl) throws IOException
	{
	return new DasGenomeImpl(dasUrl.toString());
	}


private class DasGenomeImpl implements ReferenceGenome
	{
	final String basedasurl;
	final XMLInputFactory xmlInputFactory =XMLInputFactory.newFactory();
	final SAMSequenceDictionary dict = new SAMSequenceDictionary();
	private DasContig last_contig = null;
	
	private class DasContig extends AbstractReferenceContigImpl
		{
		final SAMSequenceRecord ssr;
		DasContig(final SAMSequenceRecord ssr) {
			this.ssr= ssr;
			}
		@Override
		public SAMSequenceRecord getSAMSequenceRecord() {
			return this.ssr;
			}
		
		
		
		@Override
		protected byte[] refill(int start0, int end0) {
			InputStream in = null;
			XMLEventReader xef = null;
			try {
				final String dna_url = DasGenomeImpl.this.basedasurl+"/dna?segment="+
						URLEncoder.encode(getContig(), "UTF-8")+","+
						(start0+1)+","+end0;
				final QName DNA= new QName("DNA");
				in = new URL(dna_url).openStream();
				xef = DasGenomeImpl.this.xmlInputFactory.createXMLEventReader(in);
				while(xef.hasNext())
					{
					XMLEvent evt=xef.nextEvent();
					if(evt.isStartElement() && evt.asStartElement().equals(DNA)) 
						{
						ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(1,end0-start0));
						while(xef.hasNext())
							{
							evt=xef.nextEvent();
							if(evt.isCharacters()) {
								final String sequence = evt.asCharacters().getData();
								for(int i=0;i< sequence.length();i++)
									{
									if(Character.isWhitespace(sequence.charAt(i))) continue;
									baos.write((byte)sequence.charAt(i));
									}
								
								}
							else if(evt.isEndElement())
								{
								baos.close();
								return baos.toByteArray();
								}
							else
								{
								throw new XMLStreamException(dna_url+ " : illegal state",evt.getLocation());
								}
							}
						throw new XMLStreamException(dna_url+ " : illegal state",evt.getLocation());
						}
					}
				throw new IllegalStateException(dna_url+ " : No <DNA> found");
				}
			catch(final Exception err)
				{
				throw new RuntimeIOException(err);
				}
			finally
				{
				CloserUtil.close(xef);
				CloserUtil.close(in);
				}
			}
		}
	
	
	DasGenomeImpl(String base) throws IOException {
		if(!base.endsWith("/")) base+="/";
		this.basedasurl = base;
		InputStream in = null;
		XMLEventReader xef = null;
		try {
			final QName SEGMENT= new QName("SEGMENT");
			final QName ATT_ID= new QName("id");
			final QName ATT_STOP= new QName("stop");
			final String entry_points_url = this.basedasurl+"entry_points"; 
			in = new URL(entry_points_url).openStream();
			xef = this.xmlInputFactory.createXMLEventReader(in);
			while(xef.hasNext())
				{
				final XMLEvent evt=xef.nextEvent();
				if(!evt.isStartElement()) continue;
				final StartElement SE=evt.asStartElement();
				if(!SE.getName().equals(SEGMENT)) continue;
				Attribute att= SE.getAttributeByName(ATT_ID);
				if(att==null) throw new XMLStreamException(entry_points_url+":cannot get @id", SE.getLocation());
				final String id = att.getValue();
				att= SE.getAttributeByName(ATT_STOP);
				if(att==null) throw new XMLStreamException(entry_points_url+":cannot get @stop", SE.getLocation());
				final int length = Integer.parseInt(att.getValue());
				this.dict.addSequence(new SAMSequenceRecord(id, length));
				}
			}
		catch(final XMLStreamException err)
			{
			throw new IOException(err);
			}
		finally
			{
			CloserUtil.close(xef);
			CloserUtil.close(in);
			}
		}
	
	@Override
	public String getSource() {
		return this.basedasurl;
		}
	@Override
	public String toString() {
		return getSource();
		}
	
	@Override
	public SAMSequenceDictionary getDictionary() {
		return this.dict;
		}
	
	@Override
	public ReferenceContig getContig(String contigName) {
		if(last_contig!=null && last_contig.getContig().equals(contigName)) {
			return last_contig;
			}
		final SAMSequenceRecord ssr = this.getDictionary().getSequence(contigName);
		if(ssr==null) return null;
		last_contig = new DasContig(ssr);
		return last_contig;
		}
	@Override
	public void close() throws IOException {
		
		}
	}

// http://genome.cse.ucsc.edu/cgi-bin/das/hg19/entry_points
private void xxx_das(String dasurl) throws Exception {
	if(!dasurl.endsWith("/")) dasurl+="/";
	String entry_points_url = dasurl+"entry_points"; 
	XMLInputFactory xif=XMLInputFactory.newFactory();
	InputStream in = new URL(entry_points_url).openStream();
	XMLEventReader xef = xif.createXMLEventReader(in);
	final SAMSequenceDictionary dict=new SAMSequenceDictionary();
	BiFunction<StartElement,String,String> required_attribute = (SE,ATT)->{
		final Attribute att = SE.getAttributeByName(new QName(ATT));
		if(att==null) throw new RuntimeException("Cannot find @"+ATT+" in "+SE.getName()+" at "+SE.getLocation());
		return att.getValue();
		};
	while(xef.hasNext())
		{
		final XMLEvent evt=xef.nextEvent();
		if(evt.isStartElement())
			{
			StartElement SE=evt.asStartElement();
			if(SE.getName().getLocalPart().equals("SEGMENT"))
				{
				final String id = required_attribute.apply(SE,"id");
				final int length = Integer.parseInt(required_attribute.apply(SE,"stop"));
				dict.addSequence(new SAMSequenceRecord(id, length));
				}
			}
		}
	xef.close();
	in.close();
	}
}
