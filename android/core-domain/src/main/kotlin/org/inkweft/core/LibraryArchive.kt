// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.*

/** Typed row stream, not SQL, a SQLite database file, or an extractable ZIP.
 * Caller-supplied schema is compiled into the app, never read from the archive.
 * SHA-256 detects damage; it is NOT authentication or encryption. */
object LibraryArchive {
    const val MAX_BYTES = 134_217_728L
    const val MAX_ROWS = 500_000L
    const val MAX_FIELD = 2_000_000
    private const val MAGIC = 0x49574C42 // IWLB
    data class Column(val name:String,val kind:Char,val nullable:Boolean=false)
    data class Table(val name:String,val columns:List<Column>,val keys:List<String>)
    data class Summary(val createdAt:Long,val rows:List<Long>,val bytes:Long,val sha256:String)
    interface Rows {
        fun count(table:Int):Long
        fun visit(table:Int, consume:(List<Any?>)->Unit)
    }
    private fun signature(schema:List<Table>) = MessageDigest.getInstance("SHA-256").digest(
        schema.joinToString("\n"){t->t.name+":"+t.columns.joinToString(","){"${it.name}/${it.kind}/${it.nullable}"}+":"+t.keys.joinToString(",")}.toByteArray(Charsets.UTF_8))
    private fun ByteArray.hex()=joinToString(""){"%02x".format(it.toInt() and 255)}
    private class CountOut(val target:OutputStream):OutputStream(){
        var count=0L
        override fun write(b:Int){require(++count<=MAX_BYTES){"BACKUP_SIZE_LIMIT"};target.write(b)}
        override fun write(b:ByteArray,off:Int,len:Int){require(count+len<=MAX_BYTES){"BACKUP_SIZE_LIMIT"};target.write(b,off,len);count+=len}
        override fun flush()=target.flush()
    }
    private class CountIn(val target:InputStream):InputStream(){
        var count=0L
        override fun read():Int{val b=target.read();if(b>=0)require(++count<=MAX_BYTES){"BACKUP_SIZE_LIMIT"};return b}
        override fun read(b:ByteArray,off:Int,len:Int):Int{
            if(len==0)return 0
            val n=target.read(b,off,minOf(len.toLong(),MAX_BYTES-count+1).toInt())
            if(n==0){val one=read();if(one<0)return -1;b[off]=one.toByte();return 1}
            if(n>0){count+=n;require(count<=MAX_BYTES){"BACKUP_SIZE_LIMIT"}}
            return n
        }
    }
    private fun validateSchema(s:List<Table>){
        require(s.size in 1..64 && s.map{it.name}.distinct().size==s.size)
        s.forEach{t->require(t.name.matches(Regex("[a-z_]+"))&&t.columns.size in 1..32)
            require(t.columns.map{it.name}.distinct().size==t.columns.size)
            require(t.columns.all{it.kind in "IFSB"&&it.name.matches(Regex("[A-Za-z_]+"))})}
    }
    fun write(output:OutputStream,schema:List<Table>,source:Rows,createdAt:Long,
              checkActive:()->Unit={}):Summary {
        validateSchema(schema);require(createdAt>=0)
        val counter=CountOut(output);val hash=MessageDigest.getInstance("SHA-256")
        val hashed=DigestOutputStream(counter,hash);val d=DataOutputStream(hashed)
        d.writeInt(MAGIC);d.writeInt(1);d.writeLong(createdAt);d.write(signature(schema));d.writeInt(schema.size)
        val counts=mutableListOf<Long>();var total=0L
        schema.forEachIndexed{index,t->
            checkActive();val n=source.count(index);require(n>=0&&n<=MAX_ROWS-total){"BACKUP_ROW_LIMIT"};total+=n;counts+=n
            d.writeLong(n);var seen=0L
            source.visit(index){row->
                checkActive();require(++seen<=n&&row.size==t.columns.size){"BACKUP_ROW_MISMATCH"}
                row.forEachIndexed{j,value->val c=t.columns[j]
                    d.writeBoolean(value!=null)
                    if(value==null)require(c.nullable) else when(c.kind){
                        'I'->d.writeLong(value as Long)
                        'F'->{val f=value as Double;require(f.isFinite());d.writeDouble(f)}
                        'S','B'->{val bytes=if(c.kind=='S')(value as String).toByteArray(Charsets.UTF_8)else value as ByteArray
                            require(bytes.size<=MAX_FIELD){"BACKUP_FIELD_LIMIT"};d.writeInt(bytes.size);d.write(bytes)}
                    }
                }
            };require(seen==n){"BACKUP_ROW_MISMATCH"}
        }
        checkActive();d.flush();val digest=hash.digest();hashed.on(false);d.write(digest);d.flush()
        return Summary(createdAt,counts.toList(),counter.count,digest.hex())
    }
    /** onRow may write only to an isolated staging transaction. Footer validation
     * happens after rows; never call this with the live library as the sink. */
    fun read(input:InputStream,schema:List<Table>,onRow:(Int,List<Any?>)->Unit,
             legacySchema:List<Table>?=null, otherLegacySchemas:List<List<Table>> = emptyList(), checkActive:()->Unit={}):Summary {
        validateSchema(schema)
        val counter=CountIn(input);val hash=MessageDigest.getInstance("SHA-256")
        val hashed=DigestInputStream(counter,hash);val d=DataInputStream(hashed)
        require(d.readInt()==MAGIC&&d.readInt()==1){"BACKUP_FORMAT_UNSUPPORTED"}
        val at=d.readLong();require(at>=0)
        val sig=ByteArray(32);d.readFully(sig)
        val additional=otherLegacySchemas.firstOrNull{MessageDigest.isEqual(sig,signature(it))}
        val actualSchema=when {
            MessageDigest.isEqual(sig,signature(schema))->schema
            legacySchema!=null&&MessageDigest.isEqual(sig,signature(legacySchema))->legacySchema
            additional!=null->additional
            else->throw IllegalArgumentException("BACKUP_SCHEMA_UNSUPPORTED")
        }
        require(d.readInt()==actualSchema.size){"BACKUP_SCHEMA_UNSUPPORTED"}
        var total=0L;val counts=mutableListOf<Long>()
        actualSchema.forEachIndexed{index,t->
            checkActive();val n=d.readLong();require(n>=0&&n<=MAX_ROWS-total){"BACKUP_ROW_LIMIT"};total+=n;counts+=n
            repeat(n.toInt()){
                checkActive();val row=t.columns.map{c->
                    when(val present=d.readUnsignedByte()){
                        0->{require(c.nullable);null}
                        1->when(c.kind){
                            'I'->d.readLong()
                            'F'->d.readDouble().also{require(it.isFinite())}
                            'S','B'->{val len=d.readInt();require(len in 0..MAX_FIELD && counter.count+len<=MAX_BYTES){"BACKUP_FIELD_LIMIT"}
                                val bytes=ByteArray(len);d.readFully(bytes)
                                if(c.kind=='B')bytes else Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}
                            else->error("Unsupported compiled type")
                        }
                        else->throw IllegalArgumentException("BACKUP_NULL_FLAG_$present")
                    }
                };onRow(index,row)
            }
        }
        checkActive();val expected=hash.digest();hashed.on(false);val actual=ByteArray(32);d.readFully(actual)
        require(MessageDigest.isEqual(expected,actual)){"BACKUP_CHECKSUM_MISMATCH"}
        require(d.read()==-1){"BACKUP_TRAILING_BYTES"}
        return Summary(at,counts.toList(),counter.count,expected.hex())
    }
}
