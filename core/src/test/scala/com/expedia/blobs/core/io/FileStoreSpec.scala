package com.expedia.blobs.core.io

import java.io.{File, IOException}
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import com.expedia.blobs.core.{Blob, BlobReadWriteException}

import scala.collection.JavaConverters._
import org.parboiled.common.FileUtils
import org.scalatest.{BeforeAndAfter, FunSpec, GivenWhenThen, Matchers}

class TestableFileStore(path: String) extends FileStore(path) {

  private var failBit = false
  private var sz = 0

  override protected def storeInternal(blob: Blob): Unit = {
    if (failBit) {
      throw new BlobReadWriteException("storage failure", new IOException())
    }

    super.storeInternal(blob)
    sz += 1
  }

  override protected def readInternal(key: String): Optional[Blob] = {
    if (failBit) {
      throw new BlobReadWriteException("storage failure", new IOException())
    }

    Thread.sleep(10)
    super.readInternal(key)
  }

  def ++(blob: Blob): Unit = storeInternal(blob)

  def throwError(bool: Boolean): Unit = failBit = bool

  def size(): Int = sz
}

class FileStoreSpec extends FunSpec with GivenWhenThen with BeforeAndAfter with Matchers {
  describe("a blob store backed by file storage") {
    var store: TestableFileStore = null
    before {
      FileUtils.forceMkdir(new File("data"))
      store = new TestableFileStore("data")
    }

    after {
      store.close()
    }

    it("should store a blob") {
      Given(" a simple blob")
      val blob = Support.newBlob()
      When("it is stored using the given store")
      store.store(blob)
      Then("it should successfully store it")
      Thread.sleep(50)
      store.size should equal(1)
    }
    it("should fail to store a blob and exception is not propagated") {
      Given(" a simple blob")
      val blob = Support.newBlob()
      When("it is stored using the given store")
      store.throwError(true)
      store.store(blob)
      Then("it should not successfully store it")
      Thread.sleep(50)
      store.size should equal(0)
    }
    it("should read a blob and call the callback") {
      Given(" a store with blob already in it")
      val blob = Support.newBlob()
      store ++ blob
      val blobRead = new AtomicBoolean(false)
      When("it is read from the store with a callback")
      store.read("key1", (t: Optional[Blob], e: Throwable) => {
        blobRead.set(t.isPresent)
      })
      Then("it should successfully read it")
      Thread.sleep(50)
      blobRead.get should be(true)
    }
    it("should read a blob and return before a given timeout") {
      Given(" a store with blob already in it")
      val blob = Support.newBlob()
      store ++ blob
      When("it is read from the store with a timeout")
      val read = store.read("key1", 100, TimeUnit.MILLISECONDS)
      Then("it should successfully read it")
      read.get().getKey should equal("key1")
      read.get().getData should equal("""{"key":"value"}""".getBytes)
      read.get().getMetadata.asScala should equal(Map[String, String]("a"->"b", "c"->"d"))
    }
    it("should return an empty object if timeout occurs before the read") {
      Given(" a store with blob already in it")
      val blob = Support.newBlob()
      store ++ blob
      When("it is read from the store with a timeout")
      val read = store.read("key1", 1, TimeUnit.MILLISECONDS)
      Then("it should return an empty object")
      read.isPresent should be(false)
    }
    it("should return an empty object if the given key doesnt exist") {
      Given(" a store with some blob(s) already in it")
      val blob = Support.newBlob()
      store ++ blob
      When("when an unknown key is read")
      val read = store.read("key2")
      Then("it should return an empty object")
      read.isPresent should be(false)
    }
    it("should validate the directory at initialization") {
      Given("some invalid directories")
      FileUtils.writeAllText("some text", "data/somefile")
      When("when an instance of file store is initialized")
      Then("it should fail initialization for non-existent directory")
      intercept[IllegalArgumentException] {
        new FileStore("non-existent-directory")
      }
      And("it should fail initialization for invalid directory")
      intercept[IllegalArgumentException] {
        new FileStore("data/somefile")
      }
    }
  }
}

